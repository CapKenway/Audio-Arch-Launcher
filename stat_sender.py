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
CONFIG_DIR_USER = os.path.expanduser("~/.config/audioarch")
CONFIG_PATH_USER = os.path.join(CONFIG_DIR_USER, "stats.conf")
CONFIG_PATH_LOCAL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "stats.conf")

def get_config_path(for_write=False):
    if not for_write:
        if os.path.exists(CONFIG_PATH_USER):
            return CONFIG_PATH_USER
        if os.path.exists(CONFIG_PATH_LOCAL):
            return CONFIG_PATH_LOCAL
        return CONFIG_PATH_USER
    try:
        os.makedirs(CONFIG_DIR_USER, exist_ok=True)
        # Test writability
        test_file = os.path.join(CONFIG_DIR_USER, ".write_test")
        with open(test_file, "w") as f: f.write("1")
        os.remove(test_file)
        return CONFIG_PATH_USER
    except Exception:
        return CONFIG_PATH_LOCAL

def load_config():
    interval = DEFAULT_INTERVAL
    path = get_config_path(for_write=False)
    if os.path.exists(path):
        try:
            with open(path, "r") as f:
                for line in f:
                    line = line.strip()
                    if line.startswith("interval") and "=" in line:
                        val = line.split("=", 1)[1].strip()
                        interval = max(0.1, min(10.0, float(val)))
        except Exception:
            pass
    return {"interval": interval}

def save_config(interval):
    path = get_config_path(for_write=True)
    try:
        with open(path, "w") as f:
            f.write("# Audio Arch PC Hardware Telemetry Configuration\n")
            f.write("[stats]\n")
            f.write(f"interval = {interval:.2f}\n")
        return path
    except Exception as e:
        log_warn(f"Could not save config to {path}: {e}")
        return path

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
        self.ip, self.net_link = self._detect_network_identity()
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
        friendly_name = None

        # 1. Query vulkaninfo / glxinfo for accurate driver marketing device name
        try:
            out = subprocess.check_output(['vulkaninfo', '--summary'], text=True, stderr=subprocess.DEVNULL)
            for line in out.splitlines():
                if 'deviceName' in line and '=' in line:
                    name = line.split('=', 1)[1].strip()
                    name = re.sub(r'\(.*?\)', '', name).strip()
                    name = re.sub(r'^AMD\s+', '', name).strip()
                    if 'Radeon' in name or 'GeForce' in name or 'Intel' in name:
                        friendly_name = name
                        break
        except Exception:
            pass

        if not friendly_name:
            try:
                out = subprocess.check_output(['glxinfo', '-B'], text=True, stderr=subprocess.DEVNULL)
                for line in out.splitlines():
                    if 'Device:' in line or 'OpenGL renderer string:' in line:
                        name = line.split(':', 1)[1].strip()
                        name = re.sub(r'\(.*?\)', '', name).strip()
                        name = re.sub(r'^AMD\s+', '', name).strip()
                        if 'Radeon' in name or 'GeForce' in name or 'Intel' in name:
                            friendly_name = name
                            break
            except Exception:
                pass

        # 2. Fallback to lspci
        lspci_name = None
        try:
            out = subprocess.check_output(["lspci"], text=True, stderr=subprocess.DEVNULL)
            for line in out.splitlines():
                if "VGA compatible controller" in line or "3D controller" in line:
                    if "Radeon" in line:
                        m = re.search(r"\[(Radeon[^\]]+)\]", line)
                        if m:
                            raw = m.group(1).strip()
                            slash_parts = [p.strip() for p in raw.split("/")]
                            if any("6700 XT" in p for p in slash_parts):
                                lspci_name = "Radeon RX 6700 XT"
                            elif any("6750 XT" in p for p in slash_parts):
                                lspci_name = "Radeon RX 6750 XT"
                            else:
                                lspci_name = slash_parts[0]
                        else:
                            lspci_name = "AMD Radeon"
                    elif "NVIDIA" in line:
                        lspci_name = "NVIDIA " + line.split("NVIDIA Corporation ")[-1].split("(")[0].strip()
                    elif "Intel" in line:
                        lspci_name = "Intel Graphics"
                    break
        except Exception:
            pass

        detected_name = friendly_name or lspci_name or "PC GPU"

        # 1. AMD sysfs
        for card in sorted(glob.glob("/sys/class/drm/card*/device")):
            busy = os.path.join(card, "gpu_busy_percent")
            if os.path.exists(busy):
                return "amd", detected_name, card

        # 2. NVIDIA nvidia-smi
        try:
            res = subprocess.run(["nvidia-smi", "--query-gpu=name", "--format=csv,noheader"],
                                 capture_output=True, text=True, timeout=1)
            if res.returncode == 0 and res.stdout.strip():
                name = res.stdout.strip().splitlines()[0]
                return "nvidia", name, None
        except Exception:
            pass

        # 3. Intel sysfs
        for card in sorted(glob.glob("/sys/class/drm/card*/device")):
            if os.path.exists(os.path.join(card, "gt_act_freq_mhz")):
                return "intel", detected_name, card

        return "unknown", detected_name, None

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
        iface = ""
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
        except Exception:
            pass
        finally:
            s.close()

        try:
            out = subprocess.check_output(["ip", "route", "get", "8.8.8.8"], text=True, stderr=subprocess.DEVNULL)
            parts = out.split()
            for idx, p in enumerate(parts):
                if p == "dev" and idx + 1 < len(parts):
                    iface = parts[idx + 1]
                    break
        except Exception:
            pass

        link_info = "LAN"
        if iface:
            is_wireless = os.path.exists(f"/sys/class/net/{iface}/wireless") or os.path.exists(f"/sys/class/net/{iface}/phy80211")
            speed_mbps = -1
            try:
                with open(f"/sys/class/net/{iface}/speed", "r") as f:
                    speed_mbps = int(f.read().strip())
            except Exception:
                pass

            ssid = ""
            try:
                out = subprocess.check_output(["iwgetid", "-r"], text=True, stderr=subprocess.DEVNULL).strip()
                if out: ssid = out
            except Exception:
                pass

            if not ssid and is_wireless:
                try:
                    out = subprocess.check_output(["nmcli", "-t", "-f", "active,ssid", "dev", "wifi"], text=True, stderr=subprocess.DEVNULL)
                    for line in out.splitlines():
                        if line.startswith("yes:"):
                            ssid = line.split(":", 1)[1].strip()
                            break
                except Exception:
                    pass

            if is_wireless:
                name = ssid if ssid else "WiFi"
                if speed_mbps > 0:
                    link_info = f"{name} ({speed_mbps} Mbps)"
                else:
                    link_info = f"{name} (WiFi)"
            else:
                if speed_mbps >= 1000:
                    speed_str = f"{speed_mbps // 1000} Gbps" if speed_mbps % 1000 == 0 else f"{speed_mbps / 1000:.1f} Gbps"
                    link_info = f"Ethernet ({speed_str})"
                elif speed_mbps > 0:
                    link_info = f"Ethernet ({speed_mbps} Mbps)"
                else:
                    link_info = f"Ethernet ({iface})"

        return ip, link_info

    def get_net_speed(self):
        now = time.time()
        rx, tx = self._read_net_bytes()
        dt = max(0.1, now - self.prev_net_time)
        rx_speed_kb = ((rx - self.prev_rx_bytes) / dt) / 1024.0
        tx_speed_kb = ((tx - self.prev_tx_bytes) / dt) / 1024.0
        self.prev_rx_bytes = rx
        self.prev_tx_bytes = tx
        self.prev_net_time = now

        # Refresh IP / Link Info every 30 seconds
        if now - self.last_net_id_check > 30:
            self.ip, self.net_link = self._detect_network_identity()
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

    def get_game_and_fps(self):
        now = time.time()

        # 1. Check direct IPC file (/dev/shm/game_fps or /tmp/game_fps)
        for ipc_path in ("/dev/shm/game_fps", "/tmp/game_fps"):
            if os.path.exists(ipc_path):
                try:
                    mtime = os.path.getmtime(ipc_path)
                    if now - mtime <= 6.0:
                        with open(ipc_path, "r") as f:
                            content = f.read().strip()
                        if content:
                            if content.startswith("{"):
                                import json
                                data = json.loads(content)
                                g = data.get("game") or data.get("name") or "Game"
                                f_val = int(float(data.get("fps") or 0))
                                return str(g).strip(), max(0, f_val)
                            if "|" in content:
                                parts = content.split("|", 1)
                                g = parts[0].strip() or "Game"
                                f_val = int(float(parts[1].strip()))
                                return g, max(0, f_val)
                            elif ":" in content:
                                parts = content.split(":", 1)
                                g = parts[0].strip() or "Game"
                                f_val = int(float(parts[1].strip()))
                                return g, max(0, f_val)
                            elif content.isdigit():
                                return "Game", int(content)
                except Exception:
                    pass

        # 2. Check MangoHud CSV logs
        mangohud_dirs = [
            "/dev/shm/mangohud_logs",
            "/tmp/mangohud_logs",
            "/tmp",
            os.path.expanduser("~/.local/share/MangoHud/logs"),
            os.path.expanduser("~/.cache/MangoHud/logs")
        ]
        for d in mangohud_dirs:
            if not os.path.isdir(d):
                continue
            csvs = glob.glob(os.path.join(d, "*_*.csv"))
            if not csvs:
                continue
            try:
                latest_csv = max(csvs, key=os.path.getmtime)
                mtime = os.path.getmtime(latest_csv)
                if now - mtime <= 4.0:
                    basename = os.path.basename(latest_csv)
                    game_raw = re.sub(r'_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}\.csv$', '', basename)
                    game_raw = re.sub(r'\.csv$', '', game_raw)
                    game_name = game_raw.replace('_', ' ').strip()
                    with open(latest_csv, "r") as f:
                        lines = [ln.strip() for ln in f if ln.strip()]
                    if len(lines) >= 2:
                        header = [h.strip().lower() for h in lines[0].split(',')]
                        last_vals = [v.strip() for v in lines[-1].split(',')]
                        fps_idx = 0
                        if 'fps' in header:
                            fps_idx = header.index('fps')
                        fps = int(float(last_vals[fps_idx]))
                        return game_name if game_name else "Game", max(0, fps)
            except Exception:
                pass

        # 3. Check Gamescope stats path (/tmp/gamescope-stats or /dev/shm/gamescope-stats)
        for gs_path in ("/tmp/gamescope-stats", "/dev/shm/gamescope-stats"):
            if os.path.exists(gs_path):
                try:
                    mtime = os.path.getmtime(gs_path)
                    if now - mtime <= 4.0:
                        with open(gs_path, "r") as f:
                            lines = f.readlines()
                        for line in lines:
                            if "fps:" in line.lower() or "framerate:" in line.lower():
                                parts = line.split(":")
                                fps = int(float(parts[1].strip()))
                                return "Gamescope", max(0, fps)
                except Exception:
                    pass

        # 4. Fallback process scan: detect active game from /proc
        if not hasattr(self, "_last_proc_scan") or now - self._last_proc_scan > 2.0:
            self._last_proc_scan = now
            self._detected_game = self._scan_game_process()

        if hasattr(self, "_detected_game") and self._detected_game:
            return self._detected_game, 0

        return "No Game", 0

    def _scan_game_process(self):
        excluded_wine = {
            "winedevice.exe", "explorer.exe", "services.exe", "rpcss.exe",
            "plugplay.exe", "svchost.exe", "conhost.exe", "steam.exe",
            "steamwebhelper.exe", "rundll32.exe", "cmd.exe", "start.exe",
            "wineboot.exe", "mscorsvw.exe", "msiexec.exe"
        }
        steam_pattern = re.compile(r'/steamapps/common/([^/]+)/', re.IGNORECASE)

        try:
            for pid in os.listdir("/proc"):
                if not pid.isdigit():
                    continue
                try:
                    cmd_path = f"/proc/{pid}/cmdline"
                    with open(cmd_path, "rb") as f:
                        cmd_bytes = f.read(512)
                    if not cmd_bytes:
                        continue
                    cmd_str = cmd_bytes.replace(b'\x00', b' ').decode("utf-8", "ignore")

                    # Check Steam common games
                    m = steam_pattern.search(cmd_str)
                    if m:
                        folder = m.group(1).strip()
                        if folder not in ("SteamLinuxRuntime_sniper", "SteamLinuxRuntime_soldier", "Proton"):
                            return folder

                    # Check Wine / Proton .exe
                    if ".exe" in cmd_str.lower():
                        parts = cmd_str.split()
                        for p in parts:
                            p_lower = p.lower()
                            if p_lower.endswith(".exe"):
                                exe_name = os.path.basename(p)
                                if exe_name.lower() not in excluded_wine:
                                    clean_name = os.path.splitext(exe_name)[0]
                                    clean_name = re.sub(r'([a-z])([A-Z0-9])', r'\1 \2', clean_name)
                                    return clean_name.strip()
                except Exception:
                    continue
        except Exception:
            pass
        return None

    def get_stat_packet(self):
        cpu_pct, core_pcts = self.get_cpu_usage()
        cpu_temp = self.get_cpu_temp()
        ram_used, ram_total, ram_pct = self.get_ram()
        gpu_pct, gpu_temp, vram_used, vram_total, vram_pct = self.get_gpu()
        down_kb, up_kb = self.get_net_speed()
        disk_used, disk_total, disk_pct = self.get_disk()
        cores_str = ",".join(str(c) for c in core_pcts)
        game_name, game_fps = self.get_game_and_fps()
        game_name = game_name.replace("|", "/").replace("\n", "").strip()
        if not game_name:
            game_name = "No Game"

        # Format: STAT|cpu_pct|cpu_temp|cpu_model|ram_used_gb|ram_total_gb|ram_pct|gpu_pct|gpu_temp|vram_used_gb|vram_total_gb|vram_pct|gpu_name|net_down_kb|net_up_kb|ip|net_link|disk_used_gb|disk_total_gb|disk_pct|cores_str|game_name|game_fps
        return (f"STAT|{cpu_pct}|{cpu_temp}|{self.cpu_model}|"
                f"{ram_used:.1f}|{ram_total:.1f}|{ram_pct}|"
                f"{gpu_pct}|{gpu_temp}|{vram_used:.1f}|{vram_total:.1f}|{vram_pct}|"
                f"{self.gpu_name}|{down_kb:.1f}|{up_kb:.1f}|{self.ip}|{self.net_link}|"
                f"{disk_used:.1f}|{disk_total:.1f}|{disk_pct}|{cores_str}|"
                f"{game_name}|{game_fps}\n")


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

def run_telemetry_loop(host="127.0.0.1", port=STATS_PORT, interval=None):
    if interval is None:
        cfg = load_config()
        interval = cfg.get("interval", DEFAULT_INTERVAL)
    interval = max(0.1, min(10.0, float(interval)))

    collector = PcStatsCollector()
    log_info(f"Starting Audio Arch Telemetry Service (Target: {host}:{port}, Interval: {interval}s)")
    log_info(f"Detected CPU: {BOLD}{collector.cpu_model}{NC}")
    log_info(f"Detected GPU: {BOLD}{collector.gpu_name} ({collector.gpu_type}){NC}")
    log_info(f"Network ID:   {BOLD}IP: {collector.ip} | Link: {collector.net_link}{NC}")

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

def cmd_interval(new_interval=None):
    if new_interval is None:
        cfg = load_config()
        cur = cfg.get("interval", DEFAULT_INTERVAL)
        log_info(f"Current polling interval: {BOLD}{cur}s{NC} ({1.0/cur:.1f} Hz)")
        log_info(f"To adjust interval, run: {CYAN}python3 stat_sender.py interval <seconds>{NC}")
        return

    try:
        val = float(new_interval)
        if val < 0.1 or val > 10.0:
            log_err("Interval must be between 0.1s (10 Hz) and 10.0s (0.1 Hz)")
            return
        saved_path = save_config(val)
        log_ok(f"Saved polling interval: {BOLD}{val}s{NC} ({1.0/val:.1f} Hz) to {saved_path}")
        res = subprocess.run(["systemctl", "--user", "is-active", SERVICE_FILENAME], capture_output=True, text=True)
        if res.stdout.strip() == "active":
            log_info(f"Restarting {SERVICE_FILENAME} to apply new interval immediately...")
            subprocess.run(["systemctl", "--user", "restart", SERVICE_FILENAME], check=False)
            log_ok("Service restarted with new interval!")
    except ValueError:
        log_err(f"Invalid interval value: '{new_interval}'. Must be a number in seconds.")

def cmd_setup_mangohud():
    log_info("Configuring MangoHud for automatic real-time FPS logging...")
    mhud_dir = os.path.expanduser("~/.config/MangoHud")
    mhud_conf = os.path.join(mhud_dir, "MangoHud.conf")
    os.makedirs(mhud_dir, exist_ok=True)
    os.makedirs("/tmp/mangohud_logs", exist_ok=True)

    existing = ""
    if os.path.exists(mhud_conf):
        with open(mhud_conf, "r") as f:
            existing = f.read()

    lines_to_add = [
        "output_folder=/tmp/mangohud_logs",
        "autostart_log=0",
        "log_interval=1000",
    ]
    appended = []
    for l in lines_to_add:
        key = l.split("=")[0]
        if key not in existing:
            appended.append(l)

    if appended:
        with open(mhud_conf, "a" if existing else "w") as f:
            if existing and not existing.endswith("\n"):
                f.write("\n")
            f.write("# Audio Arch Telemetry FPS Integration\n")
            for l in appended:
                f.write(f"{l}\n")
        log_ok(f"Updated MangoHud config at: {CYAN}{mhud_conf}{NC}")
    else:
        log_ok(f"MangoHud config already configured at: {CYAN}{mhud_conf}{NC}")

    log_ok("MangoHud logging ready! Any game running with MANGOHUD=1 will stream live FPS.")
    log_info("Tip: You can also manually test/feed FPS anytime via:")
    print(f"     {CYAN}python3 stat_sender.py set-fps \"Cyberpunk 2077\" 85{NC}")
    print(f"     or {CYAN}echo 'Cyberpunk 2077|85' > /dev/shm/game_fps{NC}")

def cmd_set_fps(game_name, fps):
    content = f"{game_name}|{fps}\n"
    try:
        with open("/dev/shm/game_fps", "w") as f:
            f.write(content)
        log_ok(f"Sent to dashboard IPC (/dev/shm/game_fps): {BOLD}{game_name}{NC} @ {BOLD}{fps} FPS{NC}")
    except Exception as e:
        log_err(f"Failed to write to /dev/shm/game_fps: {e}")

def service_status():
    log_info(f"Systemd Service Status ({SERVICE_FILENAME}):")
    subprocess.run(["systemctl", "--user", "status", SERVICE_FILENAME, "--no-pager"], check=False)
    print()
    cfg = load_config()
    cur_interval = cfg.get("interval", DEFAULT_INTERVAL)
    log_info(f"Configured polling interval: {BOLD}{cur_interval}s{NC} ({1.0/cur_interval:.1f} Hz)")
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
        net_link = parts[16] if len(parts) > 16 else "--"
        game_name = parts[21] if len(parts) > 21 else "No Game"
        game_fps = parts[22] if len(parts) > 22 else "0"
        print(f"  \u2022 CPU:  {parts[1]}% ({parts[3]} @ {parts[2]}°C)")
        print(f"  \u2022 RAM:  {parts[4]} / {parts[5]} GB ({parts[6]}%)")
        print(f"  \u2022 GPU:  Core: {parts[7]}% ({parts[12]} @ {parts[8]}°C, VRAM: {parts[9]}/{parts[10]} GB)")
        print(f"  \u2022 Net:  ↓ {format_speed(down_kb)}   ↑ {format_speed(up_kb)}")
        print(f"  \u2022 Link: IP: {ip} | Link: {net_link}")
        print(f"  \u2022 Game: {game_name} ({game_fps} FPS)")


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
  interval [seconds]             Get or set telemetry polling interval (e.g. 0.5)
  set-fps <game> <fps>           Feed live game name & FPS via IPC (/dev/shm/game_fps)
  setup-mangohud                 Configure MangoHud for automatic real-time FPS
  run                            Run telemetry streamer in foreground
  once, --once                   Print single packet to stdout and exit
"""
    )
    parser.add_argument("command", nargs="?", default="run",
                        choices=["install", "uninstall", "status", "start", "stop", "restart", "run", "once", "interval", "set-fps", "setup-mangohud"],
                        help="Action to perform (default: run)")
    parser.add_argument("args", nargs="*", help="Optional arguments for commands (interval, set-fps)")
    parser.add_argument("--install-service", action="store_true", help="Alias for 'install'")
    parser.add_argument("--uninstall-service", action="store_true", help="Alias for 'uninstall'")
    parser.add_argument("--once", action="store_true", help="Alias for 'once'")
    parser.add_argument("--host", default="127.0.0.1", help="Target host IP (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=STATS_PORT, help=f"Target port (default: {STATS_PORT})")
    parser.add_argument("--interval", "-i", type=float, default=None, help="Update interval in seconds (default: from config or 1.0)")

    args = parser.parse_args()

    # Flag aliases
    if args.install_service: args.command = "install"
    elif args.uninstall_service: args.command = "uninstall"
    elif args.once: args.command = "once"

    if args.interval is not None and args.command in ("install", "run"):
        save_config(args.interval)

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
    elif args.command == "interval":
        val = args.args[0] if args.args else (str(args.interval) if args.interval is not None else None)
        cmd_interval(val)
    elif args.command == "set-fps":
        game = args.args[0] if len(args.args) > 0 else "Game"
        fps = int(args.args[1]) if len(args.args) > 1 else 60
        cmd_set_fps(game, fps)
    elif args.command == "setup-mangohud":
        cmd_setup_mangohud()
    elif args.command == "once":
        c = PcStatsCollector()
        time.sleep(0.5)
        print(c.get_stat_packet().strip())
    elif args.command == "run":
        run_telemetry_loop(host=args.host, port=args.port, interval=args.interval)

if __name__ == "__main__":
    main()
