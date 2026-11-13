#!/usr/bin/env python3
"""
Audio Arch - Realtime PC Hardware Telemetry Service & Installer
================================================================
Supplies real-time CPU, RAM, GPU, and Network throughput + IP/MAC statistics
to the Audio Arch Launcher on Android over physical USB (ADB forward) or WiFi.

Can be run standalone, or installed/managed as a systemd user service:
    python3 stat_sender.py install      # Install and start systemd background service
    python3 stat_sender.py status       # Check service & telemetry status
    python3 stat_sender.py stop         # Stop background service
    python3 stat_sender.py start        # Start background service
    python3 stat_sender.py restart      # Restart background service
    python3 stat_sender.py uninstall    # Stop and completely remove service
    python3 stat_sender.py run          # Run directly in foreground
    python3 stat_sender.py once         # Output single telemetry packet to stdout
"""

import os
import sys
import time
import socket
import glob
import re
import signal
import subprocess
import argparse
import shutil

SERVICE_NAME = "audioarch-stats"
SERVICE_FILENAME = f"{SERVICE_NAME}.service"
SYSTEMD_USER_DIR = os.path.expanduser("~/.config/systemd/user")
SERVICE_PATH = os.path.join(SYSTEMD_USER_DIR, SERVICE_FILENAME)

STATS_PORT = 12347
DEFAULT_INTERVAL = 1.0

# Terminal styling
GREEN = "\033[0;32m"
BLUE = "\033[0;34m"
YELLOW = "\033[1;33m"
CYAN = "\033[0;36m"
RED = "\033[0;31m"
BOLD = "\033[1m"
NC = "\033[0m"

def log_info(msg):  print(f"{BLUE}[*]{NC} {msg}")
def log_ok(msg):    print(f"{GREEN}[✓]{NC} {msg}")
def log_warn(msg):  print(f"{YELLOW}[!]{NC} {msg}")
def log_err(msg):   print(f"{RED}[✗]{NC} {msg}", file=sys.stderr)


# ==============================================================================
# Hardware Telemetry Collector (Zero External Dependencies)
# ==============================================================================
class PcStatsCollector:
    def __init__(self):
        self.prev_idle = 0.0
        self.prev_total = 0.0
        self.prev_rx_bytes, self.prev_tx_bytes = self._read_net_bytes()
        self.prev_net_time = time.time()
        self.cpu_model = self._detect_cpu_model()
        self.gpu_type, self.gpu_name, self.gpu_card_path = self._detect_gpu()
        self.ip, self.mac = self._detect_network_identity()
        self.last_net_id_check = time.time()
        self._init_cpu()

    def _detect_cpu_model(self):
        try:
            with open("/proc/cpuinfo") as f:
                for line in f:
                    if "model name" in line:
                        name = line.split(":", 1)[1].strip()
                        name = name.replace("Processor", "").replace("(R)", "").replace("(TM)", "").strip()
                        return name
        except Exception:
            pass
        return "PC CPU"

    def _init_cpu(self):
        self.prev_cpu_times = {}
        try:
            with open("/proc/stat") as f:
                for line in f:
                    if line.startswith("cpu"):
                        parts = line.strip().split()
                        fields = [float(c) for c in parts[1:]]
                        idle = fields[3] + fields[4]
                        total = sum(fields)
                        self.prev_cpu_times[parts[0]] = (idle, total)
        except Exception:
            pass

    def get_cpu_usage(self):
        overall_pct = 0
        core_pcts = []
        try:
            with open("/proc/stat") as f:
                for line in f:
                    if line.startswith("cpu"):
                        parts = line.strip().split()
                        name = parts[0]
                        fields = [float(c) for c in parts[1:]]
                        idle = fields[3] + fields[4]
                        total = sum(fields)
                        prev_idle, prev_total = self.prev_cpu_times.get(name, (idle, total))
                        diff_idle = idle - prev_idle
                        diff_total = total - prev_total
                        self.prev_cpu_times[name] = (idle, total)
                        pct = 0
                        if diff_total > 0:
                            pct = max(0, min(100, int((1.0 - diff_idle / diff_total) * 100)))
                        if name == "cpu":
                            overall_pct = pct
                        else:
                            core_pcts.append(pct)
        except Exception:
            pass
        return overall_pct, core_pcts

    def get_cpu_temp(self):
        for p in glob.glob("/sys/class/hwmon/hwmon*/temp*_input"):
            try:
                with open(p) as f:
                    val = int(f.read().strip()) // 1000
                    if 15 <= val <= 115:
                        return val
            except Exception:
                pass
        return -1

    def get_ram(self):
        total_kb = 0
        avail_kb = 0
        try:
            with open("/proc/meminfo") as f:
                for line in f:
                    if line.startswith("MemTotal:"):
                        total_kb = int(line.split()[1])
                    elif line.startswith("MemAvailable:"):
                        avail_kb = int(line.split()[1])
            if total_kb > 0:
                used_kb = total_kb - avail_kb
                ram_used_gb = used_kb / (1024 * 1024)
                ram_total_gb = total_kb / (1024 * 1024)
                ram_pct = int((used_kb / total_kb) * 100)
                return ram_used_gb, ram_total_gb, max(0, min(100, ram_pct))
        except Exception:
            pass
        return 0.0, 0.0, 0

    def _detect_gpu(self):
        # Scan lspci for exact friendly model name
        lspci_name = None
        try:
            out = subprocess.check_output(["lspci"], text=True)
            for line in out.splitlines():
                if "VGA compatible controller" in line or "3D controller" in line:
                    if "Radeon" in line:
                        m = re.search(r"\[(Radeon[^\]]+)\]", line)
                        if m:
                            lspci_name = m.group(1).split("/")[0].strip()
                        else:
                            lspci_name = "AMD Radeon"
                    elif "NVIDIA" in line:
                        lspci_name = "NVIDIA " + line.split("NVIDIA Corporation ")[-1].split("(")[0].strip()
                    elif "Intel" in line:
                        lspci_name = "Intel Graphics"
                    break
        except Exception:
            pass

        # 1. AMD sysfs
        for card in sorted(glob.glob("/sys/class/drm/card*/device")):
            busy = os.path.join(card, "gpu_busy_percent")
            if os.path.exists(busy):
                name = lspci_name if lspci_name else "AMD Radeon"
                return "amd", name, card

        # 2. NVIDIA nvidia-smi
        try:
            res = subprocess.run(["nvidia-smi", "--query-gpu=name", "--format=csv,noheader"],
                                 capture_output=True, text=True, timeout=1)
            if res.returncode == 0 and res.stdout.strip():
                name = lspci_name if lspci_name else res.stdout.strip().splitlines()[0]
                return "nvidia", name, None
        except Exception:
            pass

        # 3. Intel sysfs
        for card in sorted(glob.glob("/sys/class/drm/card*/device")):
            if os.path.exists(os.path.join(card, "gt_act_freq_mhz")):
                name = lspci_name if lspci_name else "Intel Graphics"
                return "intel", name, card

        return "unknown", (lspci_name if lspci_name else "GPU"), None

    def get_gpu(self):
        gpu_pct = 0
        gpu_temp = -1
        vram_used_gb = 0.0
        vram_total_gb = 0.0
        vram_pct = 0

        if self.gpu_type == "amd" and self.gpu_card_path:
            try:
                busy_path = os.path.join(self.gpu_card_path, "gpu_busy_percent")
                if os.path.exists(busy_path):
                    with open(busy_path) as f:
                        gpu_pct = int(f.read().strip())
            except Exception: pass

            try:
                vu_path = os.path.join(self.gpu_card_path, "mem_info_vram_used")
                vt_path = os.path.join(self.gpu_card_path, "mem_info_vram_total")
                if os.path.exists(vu_path) and os.path.exists(vt_path):
                    with open(vu_path) as f: vu = int(f.read().strip())
                    with open(vt_path) as f: vt = int(f.read().strip())
                    if vt > 0:
                        vram_used_gb = vu / (1024 ** 3)
                        vram_total_gb = vt / (1024 ** 3)
                        vram_pct = int((vu / vt) * 100)
            except Exception: pass

            for tp in glob.glob(os.path.join(self.gpu_card_path, "hwmon/hwmon*/temp1_input")):
                try:
                    with open(tp) as f:
                        gpu_temp = int(f.read().strip()) // 1000
                        break
                except Exception: pass

        elif self.gpu_type == "nvidia":
            try:
                out = subprocess.check_output(
                    ["nvidia-smi", "--query-gpu=utilization.gpu,temperature.gpu,memory.used,memory.total",
                     "--format=csv,noheader,nounits"],
                    text=True, timeout=1
                ).strip()
                parts = [p.strip() for p in out.split(",")]
                if len(parts) >= 4:
                    gpu_pct = int(parts[0])
                    gpu_temp = int(parts[1])
                    vu_mb = float(parts[2])
                    vt_mb = float(parts[3])
                    vram_used_gb = vu_mb / 1024.0
                    vram_total_gb = vt_mb / 1024.0
                    if vt_mb > 0:
                        vram_pct = int((vu_mb / vt_mb) * 100)
            except Exception: pass

        return max(0, min(100, gpu_pct)), gpu_temp, vram_used_gb, vram_total_gb, max(0, min(100, vram_pct))

    def _read_net_bytes(self):
        rx = 0
        tx = 0
        try:
            with open("/proc/net/dev") as f:
                for line in f:
                    if ":" not in line: continue
                    iface, data = line.split(":", 1)
                    iface = iface.strip()
                    if iface == "lo" or iface.startswith("docker") or iface.startswith("br-") or iface.startswith("veth"):
                        continue
                    cols = data.split()
                    rx += int(cols[0])
                    tx += int(cols[8])
        except Exception:
            pass
        return rx, tx

    def _detect_network_identity(self):
        ip = "127.0.0.1"
        mac = "--"
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
        except Exception:
            pass
        finally:
            s.close()

        iface = ""
        try:
            out = subprocess.check_output(["ip", "route", "get", "8.8.8.8"], text=True)
            parts = out.split()
            for idx, p in enumerate(parts):
                if p == "dev" and idx + 1 < len(parts):
                    iface = parts[idx + 1]
                    break
        except Exception:
            pass

        if iface:
            try:
                with open(f"/sys/class/net/{iface}/address") as f:
                    mac = f.read().strip()
            except Exception:
                pass

        return ip, mac

    def get_net_speed(self):
        now = time.time()
        rx, tx = self._read_net_bytes()
        dt = max(0.1, now - self.prev_net_time)
        rx_speed_kb = ((rx - self.prev_rx_bytes) / dt) / 1024.0
        tx_speed_kb = ((tx - self.prev_tx_bytes) / dt) / 1024.0
        self.prev_rx_bytes = rx
        self.prev_tx_bytes = tx
        self.prev_net_time = now

        # Refresh IP/MAC every 30 seconds
        if now - self.last_net_id_check > 30:
            self.ip, self.mac = self._detect_network_identity()
            self.last_net_id_check = now

        return max(0.0, rx_speed_kb), max(0.0, tx_speed_kb)

    def get_disk(self):
        try:
            total, used, free = shutil.disk_usage('/')
            used_gb = used / (1024 ** 3)
            total_gb = total / (1024 ** 3)
            pct = int((used / total) * 100) if total > 0 else 0
            return used_gb, total_gb, pct
        except Exception:
            return 0.0, 0.0, 0

    def get_stat_packet(self):
        cpu_pct, core_pcts = self.get_cpu_usage()
        cpu_temp = self.get_cpu_temp()
        ram_used, ram_total, ram_pct = self.get_ram()
        gpu_pct, gpu_temp, vram_used, vram_total, vram_pct = self.get_gpu()
        down_kb, up_kb = self.get_net_speed()
        disk_used, disk_total, disk_pct = self.get_disk()
        cores_str = ",".join(str(c) for c in core_pcts)

        # Format: STAT|cpu_pct|cpu_temp|cpu_model|ram_used_gb|ram_total_gb|ram_pct|gpu_pct|gpu_temp|vram_used_gb|vram_total_gb|vram_pct|gpu_name|net_down_kb|net_up_kb|ip|mac|disk_used_gb|disk_total_gb|disk_pct|cores_str
        return (f"STAT|{cpu_pct}|{cpu_temp}|{self.cpu_model}|"
                f"{ram_used:.1f}|{ram_total:.1f}|{ram_pct}|"
                f"{gpu_pct}|{gpu_temp}|{vram_used:.1f}|{vram_total:.1f}|{vram_pct}|"
                f"{self.gpu_name}|{down_kb:.1f}|{up_kb:.1f}|{self.ip}|{self.mac}|"
                f"{disk_used:.1f}|{disk_total:.1f}|{disk_pct}|{cores_str}\n")


# ==============================================================================
# Device Link & Daemon Streaming Engine
# ==============================================================================
def is_stats_port_forwarded(port=STATS_PORT):
    try:
        res = subprocess.run(["adb", "forward", "--list"], capture_output=True, text=True, timeout=2)
        return res.returncode == 0 and f"tcp:{port}" in res.stdout
    except Exception:
        return False

def ensure_adb_stats_port(port=STATS_PORT):
    # Only run adb forward if tcp:{port} is NOT already in forward list!
    if is_stats_port_forwarded(port):
        return True
    try:
        res = subprocess.run(["adb", "forward", f"tcp:{port}", f"tcp:{port}"],
                             check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=2)
        return True
    except Exception:
        return False

def run_telemetry_loop(host="127.0.0.1", port=STATS_PORT, interval=DEFAULT_INTERVAL):
    collector = PcStatsCollector()
    log_info(f"Starting Audio Arch Telemetry Service (Target: {host}:{port}, Interval: {interval}s)")
    log_info(f"Detected CPU: {BOLD}{collector.cpu_model}{NC}")
    log_info(f"Detected GPU: {BOLD}{collector.gpu_name} ({collector.gpu_type}){NC}")
    log_info(f"Network ID:   {BOLD}IP: {collector.ip} | MAC: {collector.mac}{NC}")

    running = True
    def handle_sig(sig, frame):
        nonlocal running
        log_info("Stopping telemetry streamer...")
        running = False
    signal.signal(signal.SIGINT, handle_sig)
    signal.signal(signal.SIGTERM, handle_sig)

    last_forward_check = 0.0

    while running:
        # If targeting localhost USB tunnel, ensure port is mapped in ADB once without spamming
        if host == "127.0.0.1":
            now = time.time()
            if now - last_forward_check > 15.0:
                ensure_adb_stats_port(port)
                last_forward_check = now

        sock = None
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            sock.settimeout(3.0)
            sock.connect((host, port))
            log_ok(f"Connected to phone dashboard on port {port}! Supplying live hardware telemetry...")

            # Clean pure-TCP streaming loop: ZERO adb subprocess calls while connected!
            # If phone disconnects, sock.sendall() will raise BrokenPipeError/socket.error immediately.
            while running:
                packet = collector.get_stat_packet()
                sock.sendall(packet.encode("utf-8"))
                time.sleep(interval)

        except (socket.error, ConnectionRefusedError, BrokenPipeError, socket.timeout):
            # Polite backoff: do not spam the system or USB bus when phone/app is not listening
            time.sleep(3.0)
        finally:
            if sock:
                try: sock.close()
                except Exception: pass

    log_ok("Telemetry service gracefully terminated.")


# ==============================================================================
# Systemd User Service Management (Install / Uninstall / Status / Control)
# ==============================================================================
def generate_systemd_unit():
    script_abs = os.path.abspath(__file__)
    python_abs = sys.executable
    return f"""[Unit]
Description=Audio Arch - Realtime PC Hardware Telemetry Service for Android Dashboard
After=network.target

[Service]
Type=simple
ExecStart={python_abs} "{script_abs}" run
Restart=on-failure
RestartSec=3
Environment=PYTHONUNBUFFERED=1

[Install]
WantedBy=default.target
"""

def service_install():
    log_info("Installing Audio Arch Stats systemd user service...")
    os.makedirs(SYSTEMD_USER_DIR, exist_ok=True)
    unit_content = generate_systemd_unit()

    try:
        with open(SERVICE_PATH, "w") as f:
            f.write(unit_content)
        log_ok(f"Created unit file at: {CYAN}{SERVICE_PATH}{NC}")

        subprocess.run(["systemctl", "--user", "daemon-reload"], check=True)
        subprocess.run(["systemctl", "--user", "enable", "--now", SERVICE_FILENAME], check=True)
        log_ok(f"Service {BOLD}{SERVICE_NAME}{NC} enabled and started successfully!")
        print()
        service_status()
    except Exception as e:
        log_err(f"Failed to install service: {e}")
        sys.exit(1)

def service_uninstall():
    log_info("Uninstalling Audio Arch Stats systemd user service...")
    try:
        subprocess.run(["systemctl", "--user", "stop", SERVICE_FILENAME], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(["systemctl", "--user", "disable", SERVICE_FILENAME], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if os.path.exists(SERVICE_PATH):
            os.remove(SERVICE_PATH)
            log_ok(f"Removed unit file: {SERVICE_PATH}")
        subprocess.run(["systemctl", "--user", "daemon-reload"], check=False)
        log_ok(f"Service {BOLD}{SERVICE_NAME}{NC} completely uninstalled.")
    except Exception as e:
        log_err(f"Uninstall warning: {e}")

def service_start():
    subprocess.run(["systemctl", "--user", "start", SERVICE_FILENAME], check=False)
    service_status()

def service_stop():
    subprocess.run(["systemctl", "--user", "stop", SERVICE_FILENAME], check=False)
    log_ok(f"Service {BOLD}{SERVICE_NAME}{NC} stopped.")

def service_restart():
    subprocess.run(["systemctl", "--user", "restart", SERVICE_FILENAME], check=False)
    service_status()

def format_speed(kbps):
    if kbps <= 0.05: return "0 KB/s"
    if kbps >= 1024 * 1024: return f"{kbps / (1024 * 1024):.2f} GB/s"
    if kbps >= 1024: return f"{kbps / 1024:.1f} MB/s"
    return f"{kbps:.0f} KB/s"

def service_status():
    log_info(f"Systemd Service Status ({SERVICE_FILENAME}):")
    subprocess.run(["systemctl", "--user", "status", SERVICE_FILENAME, "--no-pager"], check=False)
    print()
    log_info("Sampling live hardware telemetry packet:")
    c = PcStatsCollector()
    time.sleep(0.5)
    pkt = c.get_stat_packet().strip()
    print(f"  {CYAN}{pkt}{NC}")
    parts = pkt.split("|")
    if len(parts) >= 15:
        down_kb = float(parts[13])
        up_kb = float(parts[14])
        ip = parts[15] if len(parts) > 15 else "--"
        mac = parts[16] if len(parts) > 16 else "--"
        print(f"  \u2022 CPU:  {parts[1]}% ({parts[3]} @ {parts[2]}°C)")
        print(f"  \u2022 RAM:  {parts[4]} / {parts[5]} GB ({parts[6]}%)")
        print(f"  \u2022 GPU:  Core: {parts[7]}% ({parts[12]} @ {parts[8]}°C, VRAM: {parts[9]}/{parts[10]} GB)")
        print(f"  \u2022 Net:  ↓ {format_speed(down_kb)}   ↑ {format_speed(up_kb)}")
        print(f"  \u2022 Link: IP: {ip} | MAC: {mac}")


# ==============================================================================
# Main Entry Point
# ==============================================================================
def main():
    parser = argparse.ArgumentParser(
        description="Audio Arch Hardware Telemetry Service & Installer",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""Commands:
  install, --install-service     Install & activate as systemd user service
  uninstall, --uninstall-service Stop & remove systemd service
  status                         Check service & sensor status
  start                          Start the systemd background service
  stop                           Stop the systemd background service
  restart                        Restart the systemd background service
  run                            Run telemetry streamer in foreground
  once, --once                   Print single packet to stdout and exit
"""
    )
    parser.add_argument("command", nargs="?", default="run",
                        choices=["install", "uninstall", "status", "start", "stop", "restart", "run", "once"],
                        help="Action to perform (default: run)")
    parser.add_argument("--install-service", action="store_true", help="Alias for 'install'")
    parser.add_argument("--uninstall-service", action="store_true", help="Alias for 'uninstall'")
    parser.add_argument("--once", action="store_true", help="Alias for 'once'")
    parser.add_argument("--host", default="127.0.0.1", help="Target host IP (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=STATS_PORT, help=f"Target port (default: {STATS_PORT})")
    parser.add_argument("--interval", type=float, default=DEFAULT_INTERVAL, help="Update interval in seconds (default: 1.0)")

    args = parser.parse_args()

    # Flag aliases
    if args.install_service: args.command = "install"
    elif args.uninstall_service: args.command = "uninstall"
    elif args.once: args.command = "once"

    if args.command == "install":
        service_install()
    elif args.command == "uninstall":
        service_uninstall()
    elif args.command == "status":
        service_status()
    elif args.command == "start":
        service_start()
    elif args.command == "stop":
        service_stop()
    elif args.command == "restart":
        service_restart()
    elif args.command == "once":
        c = PcStatsCollector()
        time.sleep(0.5)
        print(c.get_stat_packet().strip())
    elif args.command == "run":
        run_telemetry_loop(host=args.host, port=args.port, interval=args.interval)

if __name__ == "__main__":
    main()
