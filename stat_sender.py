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
import select

SERVICE_NAME = "audioarch-stats"
SERVICE_FILENAME = f"{SERVICE_NAME}.service"
SYSTEMD_USER_DIR = os.path.expanduser("~/.config/systemd/user")
SERVICE_PATH = os.path.join(SYSTEMD_USER_DIR, SERVICE_FILENAME)

STATS_PORT = 12347
DEFAULT_INTERVAL = 0.0
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
        self.cached_rx_speed = 0.0
        self.cached_tx_speed = 0.0
        self.cpu_model = self._detect_cpu_model()
        self.gpu_type, self.gpu_name, self.gpu_card_path = self._detect_gpu()
        self.cpu_temp_path = self._find_cpu_temp_path()
        self.gpu_temp_path, self.gpu_freq1_path, self.gpu_freq2_path = self._find_gpu_hwmon_paths()
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
                        name = re.sub(r'\s+\d+-Core.*$', '', name)
                        name = re.sub(r'\s+\d+-Thread.*$', '', name)
                        return name.strip()
        except Exception:
            pass
        return "PC CPU"

    def get_cpu_freq_khz(self):
        try:
            freqs = []
            for p in glob.glob("/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq"):
                try:
                    with open(p) as f:
                        freqs.append(int(f.read().strip()))
                except Exception:
                    pass
            if freqs:
                return int(sum(freqs) / len(freqs))
        except Exception:
            pass
        try:
            with open("/proc/cpuinfo") as f:
                mhz_list = [float(l.split(":")[1]) for l in f if "cpu mhz" in l.lower()]
                if mhz_list:
                    return int((sum(mhz_list) / len(mhz_list)) * 1000)
        except Exception:
            pass
        return 0

    def get_cpu_freq_ghz(self):
        khz = self.get_cpu_freq_khz()
        return khz / 1_000_000.0 if khz > 0 else 0.0

    def _init_cpu(self):
        self.prev_cpu_times = {}
        self.prev_cpu_pcts = {}
        self.last_cpu_sample_time = 0.0
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
        now = time.time()
        # Ensure at least 35ms delta so /proc/stat counters tick reliably during high-speed streaming
        if now - self.last_cpu_sample_time >= 0.035 or not self.prev_cpu_pcts:
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
                            if diff_total > 0:
                                self.prev_cpu_pcts[name] = max(0, min(100, int((1.0 - diff_idle / diff_total) * 100)))
                self.last_cpu_sample_time = now
            except Exception:
                pass

        overall_pct = self.prev_cpu_pcts.get("cpu", 0)
        core_pcts = [self.prev_cpu_pcts.get(f"cpu{i}", 0) for i in range(128) if f"cpu{i}" in self.prev_cpu_pcts]
        return overall_pct, core_pcts

    def _find_cpu_temp_path(self):
        # Prefer on-die CPU sensors (zenpower, k10temp, coretemp) to avoid slow motherboard SMBus/SuperIO queries
        for h in glob.glob("/sys/class/hwmon/hwmon*"):
            name_file = os.path.join(h, "name")
            if os.path.exists(name_file):
                try:
                    with open(name_file) as f:
                        n = f.read().strip().lower()
                    if n in ("zenpower", "k10temp", "coretemp"):
                        t1 = os.path.join(h, "temp1_input")
                        if os.path.exists(t1):
                            return t1
                except Exception:
                    pass
        for p in glob.glob("/sys/class/hwmon/hwmon*/temp*_input"):
            return p
        return None

    def _find_gpu_hwmon_paths(self):
        temp_p = None
        f1_p = None
        f2_p = None
        if self.gpu_type == "amd" and self.gpu_card_path:
            for hwmon in glob.glob(os.path.join(self.gpu_card_path, "hwmon/hwmon*")):
                t1 = os.path.join(hwmon, "temp1_input")
                if os.path.exists(t1): temp_p = t1
                f1 = os.path.join(hwmon, "freq1_input")
                if os.path.exists(f1): f1_p = f1
                f2 = os.path.join(hwmon, "freq2_input")
                if os.path.exists(f2): f2_p = f2
                if temp_p and f1_p and f2_p:
                    break
        return temp_p, f1_p, f2_p

    def get_cpu_temp(self):
        if self.cpu_temp_path:
            try:
                with open(self.cpu_temp_path) as f:
                    val = int(f.read().strip()) // 1000
                    if 15 <= val <= 115:
                        return val
            except Exception:
                self.cpu_temp_path = self._find_cpu_temp_path()
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

            if self.gpu_temp_path:
                try:
                    with open(self.gpu_temp_path) as f:
                        gpu_temp = int(f.read().strip()) // 1000
                except Exception: pass
            else:
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

    def get_gpu_clocks_khz(self):
        """
        Returns (core_clock_khz, mem_clock_khz) in exact kilohertz integers.
        Core clock: sclk / freq1_input (> 1.5GHz under load).
        Memory clock: mclk / freq2_input (~0.5 - 1.0GHz).
        """
        core_khz = 0
        mem_khz = 0

        if self.gpu_type == "amd" and self.gpu_card_path:
            # 1. Fast direct read via cached hwmon freq paths
            if self.gpu_freq1_path and self.gpu_freq2_path:
                try:
                    with open(self.gpu_freq1_path) as f: core_khz = int(f.read().strip()) // 1000
                    with open(self.gpu_freq2_path) as f: mem_khz = int(f.read().strip()) // 1000
                    return core_khz, mem_khz
                except Exception:
                    pass

            # 2. Inspect hwmon freq*_label and freq*_input
            try:
                for hwmon in glob.glob(os.path.join(self.gpu_card_path, "hwmon/hwmon*")):
                    for lf in glob.glob(os.path.join(hwmon, "freq*_label")):
                        prefix = lf[:-6]
                        try:
                            with open(lf) as f:
                                lbl = f.read().strip().lower()
                            with open(prefix + "_input") as f:
                                val_khz = int(f.read().strip()) // 1000
                            if "sclk" in lbl or "core" in lbl or "gfx" in lbl or "freq1" in prefix:
                                core_khz = val_khz
                            elif "mclk" in lbl or "mem" in lbl or "freq2" in prefix:
                                mem_khz = val_khz
                        except Exception:
                            pass
            except Exception:
                pass

            # 2. Direct fallback to freq1_input (sclk / core) and freq2_input (mclk / mem)
            if core_khz <= 0 or mem_khz <= 0:
                try:
                    for hwmon in glob.glob(os.path.join(self.gpu_card_path, "hwmon/hwmon*")):
                        f1 = os.path.join(hwmon, "freq1_input")
                        f2 = os.path.join(hwmon, "freq2_input")
                        if core_khz <= 0 and os.path.exists(f1):
                            with open(f1) as f: core_khz = int(f.read().strip()) // 1000
                        if mem_khz <= 0 and os.path.exists(f2):
                            with open(f2) as f: mem_khz = int(f.read().strip()) // 1000
                except Exception:
                    pass

            # 3. Fallback to pp_dpm_sclk and pp_dpm_mclk
            if core_khz <= 0:
                try:
                    sclk_p = os.path.join(self.gpu_card_path, "pp_dpm_sclk")
                    if os.path.exists(sclk_p):
                        with open(sclk_p) as f:
                            for line in f:
                                if "*" in line:
                                    m = re.search(r'(\d+)\s*Mhz', line, re.I)
                                    if m: core_khz = int(m.group(1)) * 1000
                except Exception:
                    pass
            if mem_khz <= 0:
                try:
                    mclk_p = os.path.join(self.gpu_card_path, "pp_dpm_mclk")
                    if os.path.exists(mclk_p):
                        with open(mclk_p) as f:
                            for line in f:
                                if "*" in line:
                                    m = re.search(r'(\d+)\s*Mhz', line, re.I)
                                    if m: mem_khz = int(m.group(1)) * 1000
                except Exception:
                    pass

        elif self.gpu_type == "nvidia":
            try:
                out = subprocess.check_output(
                    ["nvidia-smi", "--query-gpu=clocks.current.graphics,clocks.current.memory",
                     "--format=csv,noheader,nounits"],
                    text=True, timeout=1
                ).strip()
                parts = [p.strip() for p in out.split(",")]
                if len(parts) >= 2:
                    core_khz = int(float(parts[0]) * 1000)
                    mem_khz = int(float(parts[1]) * 1000)
            except Exception:
                pass

        elif self.gpu_type == "intel" and self.gpu_card_path:
            try:
                freq_p = os.path.join(self.gpu_card_path, "gt_act_freq_mhz")
                if os.path.exists(freq_p):
                    with open(freq_p) as f:
                        core_khz = int(float(f.read().strip()) * 1000)
            except Exception:
                pass

        return core_khz, mem_khz

    def get_gpu_clocks(self):
        core_khz, mem_khz = self.get_gpu_clocks_khz()
        return core_khz / 1_000_000.0, mem_khz / 1_000_000.0

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
        dt = now - self.prev_net_time
        if dt >= 0.2:
            rx, tx = self._read_net_bytes()
            self.cached_rx_speed = max(0.0, ((rx - self.prev_rx_bytes) / dt) / 1024.0)
            self.cached_tx_speed = max(0.0, ((tx - self.prev_tx_bytes) / dt) / 1024.0)
            self.prev_rx_bytes = rx
            self.prev_tx_bytes = tx
            self.prev_net_time = now

        # Refresh IP / Link Info every 30 seconds
        if now - self.last_net_id_check > 30:
            self.ip, self.net_link = self._detect_network_identity()
            self.last_net_id_check = now

        return self.cached_rx_speed, self.cached_tx_speed

    def get_disk(self):
        try:
            total, used, free = shutil.disk_usage('/')
            used_gb = used / (1024 ** 3)
            total_gb = total / (1024 ** 3)
            pct = int((used / total) * 100) if total > 0 else 0
            return used_gb, total_gb, pct
        except Exception:
            return 0.0, 0.0, 0

    def _is_steam_runtime_or_tool(self, name):
        if not name:
            return True
        clean = re.sub(r'[^a-z0-9]', '', str(name).lower())
        runtime_signatures = (
            "steamlinuxruntime",
            "steamruntime",
            "steamlinux",
            "pressurevessel",
            "proton",
            "steamworks",
            "steamcontroller",
            "steamvr",
            "directx",
            "commonredist",
            "reaper",
            "wine64preloader",
            "winedevice",
            "explorer",
            "services",
            "svchost",
        )
        for sig in runtime_signatures:
            if clean.startswith(sig) or sig in clean:
                if sig == "proton" and not clean.startswith("proton"):
                    continue
                return True
        return False

    def _get_steam_app_name(self, app_id):
        if not app_id or not app_id.isdigit():
            return None
        if not hasattr(self, "_steam_app_cache"):
            self._steam_app_cache = {}
        if app_id in self._steam_app_cache:
            return self._steam_app_cache[app_id]

        known_tools = {
            "228980": "Steamworks Common Redistributables",
            "1070560": "Steam Linux Runtime",
            "1391110": "Steam Linux Runtime - Soldier",
            "1628350": "Steam Linux Runtime - Sniper",
            "2348590": "Steam Linux Runtime - Medicis",
            "1493710": "Proton Experimental",
            "2805730": "Proton 9.0",
        }
        if app_id in known_tools:
            self._steam_app_cache[app_id] = None
            return None

        candidate_dirs = [
            os.path.expanduser("~/.local/share/Steam/steamapps"),
            os.path.expanduser("~/.steam/steam/steamapps"),
            os.path.expanduser("~/.steam/root/steamapps"),
            os.path.expanduser("~/.var/app/com.valvesoftware.Steam/.local/share/Steam/steamapps"),
        ]
        for d in candidate_dirs:
            manifest = os.path.join(d, f"appmanifest_{app_id}.acf")
            if os.path.exists(manifest):
                try:
                    with open(manifest, "r", encoding="utf-8", errors="ignore") as f:
                        for line in f:
                            if '"name"' in line:
                                parts = line.split('"name"')
                                if len(parts) > 1:
                                    val = parts[1].replace('"', '').strip()
                                    if not self._is_steam_runtime_or_tool(val):
                                        self._steam_app_cache[app_id] = val
                                        return val
                except Exception:
                    pass

        self._steam_app_cache[app_id] = None
        return None

    def _trim_ram_csv(self, csv_path):
        """Caps active in-RAM CSV files at ~32 KB so RAM usage remains negligible."""
        try:
            size = os.path.getsize(csv_path)
            if size > 32768:
                with open(csv_path, "r", encoding="utf-8", errors="ignore") as f:
                    lines = f.readlines()
                if len(lines) > 40:
                    trimmed = lines[:3] + lines[-30:]
                    with open(csv_path, "w", encoding="utf-8") as f:
                        f.writelines(trimmed)
        except Exception:
            pass

    def _cleanup_stale_csvs(self, active_csv=None):
        """Purges old CSV files from RAM and home so files never accumulate."""
        now = time.time()
        if hasattr(self, "_last_csv_cleanup") and now - self._last_csv_cleanup < 15:
            return
        self._last_csv_cleanup = now

        cleanup_dirs = [
            "/dev/shm/mangohud_logs",
            os.path.expanduser("~/.local/share/MangoHud/logs"),
            os.path.expanduser("~/mangologs"),
            os.path.expanduser("~")
        ]
        for d in cleanup_dirs:
            if not os.path.isdir(d):
                continue
            try:
                for fname in os.listdir(d):
                    if fname.endswith(".csv") and (re.search(r'_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}\.csv$', fname) or fname.endswith("_summary.csv")):
                        full_p = os.path.join(d, fname)
                        if full_p == active_csv:
                            continue
                        try:
                            if now - os.path.getmtime(full_p) > 30:
                                os.remove(full_p)
                        except Exception:
                            pass
            except Exception:
                pass

    def _read_last_csv_fps(self, csv_path):
        try:
            with open(csv_path, "r", encoding="utf-8", errors="ignore") as f:
                fps_idx = 0
                for _ in range(5):
                    line = f.readline()
                    if not line:
                        break
                    line_parts = [h.strip().lower() for h in line.split(',')]
                    if 'fps' in line_parts:
                        fps_idx = line_parts.index('fps')
                        break

                f.seek(0, os.SEEK_END)
                size = f.tell()
                read_size = min(size, 4096)
                f.seek(max(0, size - read_size), os.SEEK_SET)
                lines = f.read().splitlines()
                if not lines:
                    return -1
                for l in reversed(lines):
                    l_str = l.strip()
                    if not l_str:
                        continue
                    vals = [v.strip() for v in l_str.split(',')]
                    if fps_idx < len(vals):
                        try:
                            val_f = float(vals[fps_idx])
                            return max(0, int(round(val_f)))
                        except ValueError:
                            continue
        except Exception:
            pass
        return -1

    def get_game_and_fps(self):
        now = time.time()

        # Update process scan periodically (every 2s) to identify running game title
        if not hasattr(self, "_last_proc_scan") or now - self._last_proc_scan > 2.0:
            self._last_proc_scan = now
            self._detected_game = self._scan_game_process()

        detected_title = getattr(self, "_detected_game", None)

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
                                g = data.get("game") or data.get("name") or detected_title or "Game"
                                f_val = int(float(data.get("fps") or 0))
                                return str(g).strip(), max(0, f_val)
                            if "|" in content:
                                parts = content.split("|", 1)
                                g = parts[0].strip() or detected_title or "Game"
                                f_val = int(float(parts[1].strip()))
                                return g, max(0, f_val)
                            elif ":" in content:
                                parts = content.split(":", 1)
                                g = parts[0].strip() or detected_title or "Game"
                                f_val = int(float(parts[1].strip()))
                                return g, max(0, f_val)
                            elif content.isdigit():
                                g = detected_title or "Game"
                                return g, int(content)
                except Exception:
                    pass

        # 2. Check MangoHud CSV logs across all potential locations
        mangohud_dirs = [
            os.path.expanduser("~/.local/share/MangoHud/logs"),
            os.path.expanduser("~/mangologs"),
            os.path.expanduser("~"),
            "/tmp/mangohud_logs",
            "/dev/shm/mangohud_logs",
            "/tmp",
        ]
        all_candidate_csvs = []
        for d in mangohud_dirs:
            if not os.path.isdir(d):
                continue
            csvs = glob.glob(os.path.join(d, "*.csv"))
            for f in csvs:
                if f.endswith("_summary.csv"):
                    continue
                # Must be a MangoHud timestamped log or contain mangohud
                if re.search(r'_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}\.csv$', f) or "mangohud" in f.lower():
                    try:
                        mtime = os.path.getmtime(f)
                        if now - mtime <= 6.0:
                            all_candidate_csvs.append((f, mtime))
                    except Exception:
                        pass

        if all_candidate_csvs:
            # Pick the most recently modified CSV
            latest_csv, _ = max(all_candidate_csvs, key=lambda x: x[1])
            fps = self._read_last_csv_fps(latest_csv)
            if fps >= 0:
                # Maintain negligible RAM footprint and delete stale files
                self._trim_ram_csv(latest_csv)
                self._cleanup_stale_csvs(latest_csv)

                basename = os.path.basename(latest_csv)
                game_raw = re.sub(r'_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}\.csv$', '', basename)
                game_raw = re.sub(r'\.csv$', '', game_raw)
                mango_title = game_raw.replace('_', ' ').strip()
                final_title = detected_title if detected_title else mango_title
                return final_title if final_title else "Game", max(0, fps)

        # Clean up stale files when no game is logging
        self._cleanup_stale_csvs(None)

        if all_candidate_csvs:
            # Pick the most recently modified CSV
            latest_csv, _ = max(all_candidate_csvs, key=lambda x: x[1])
            fps = self._read_last_csv_fps(latest_csv)
            if fps >= 0:
                basename = os.path.basename(latest_csv)
                game_raw = re.sub(r'_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}\.csv$', '', basename)
                game_raw = re.sub(r'\.csv$', '', game_raw)
                mango_title = game_raw.replace('_', ' ').strip()
                final_title = detected_title if detected_title else mango_title
                return final_title if final_title else "Game", max(0, fps)

        # Clean up stale files when no game is logging
        self._cleanup_stale_csvs(None)

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
                                g = detected_title if detected_title else "Gamescope"
                                return g, max(0, fps)
                except Exception:
                    pass

        # 4. If game process was detected but no live FPS telemetry provider is active yet
        if detected_title:
            return detected_title, 0

        return "No Game", 0

    def _scan_game_process(self):
        excluded_wine = {
            "winedevice.exe", "explorer.exe", "services.exe", "rpcss.exe",
            "plugplay.exe", "svchost.exe", "conhost.exe", "steam.exe",
            "steamwebhelper.exe", "rundll32.exe", "cmd.exe", "start.exe",
            "wineboot.exe", "mscorsvw.exe", "msiexec.exe", "tabtip.exe",
            "crashpad_handler.exe", "pressure-vessel-adverb", "bwrap"
        }
        steam_pattern = re.compile(r'/steamapps/common/([^/]+)/', re.IGNORECASE)

        try:
            for pid in os.listdir("/proc"):
                if not pid.isdigit():
                    continue
                try:
                    cmd_path = f"/proc/{pid}/cmdline"
                    with open(cmd_path, "rb") as f:
                        cmd_bytes = f.read(4096)
                    if not cmd_bytes:
                        continue
                    cmd_str = cmd_bytes.replace(b'\x00', b' ').decode("utf-8", "ignore")

                    # 1. Check SteamAppId in command line
                    m_app = re.search(r'AppId=(\d+)', cmd_str)
                    if m_app:
                        app_name = self._get_steam_app_name(m_app.group(1))
                        if app_name:
                            return app_name

                    # Also check /proc/{pid}/environ for SteamAppId=
                    try:
                        env_path = f"/proc/{pid}/environ"
                        with open(env_path, "rb") as ef:
                            env_bytes = ef.read(2048)
                        m_env = re.search(rb'SteamAppId=(\d+)', env_bytes)
                        if m_env:
                            app_id = m_env.group(1).decode("utf-8")
                            app_name = self._get_steam_app_name(app_id)
                            if app_name:
                                return app_name
                    except Exception:
                        pass

                    # 2. Check Steam common game directories
                    matches = steam_pattern.findall(cmd_str)
                    for folder in matches:
                        folder = folder.strip()
                        if not self._is_steam_runtime_or_tool(folder):
                            return folder

                    # 3. Check Wine / Proton .exe
                    if ".exe" in cmd_str.lower():
                        parts = cmd_str.split()
                        for p in parts:
                            p_clean = p.strip().strip('"\'')
                            if p_clean.lower().endswith(".exe"):
                                exe_name = os.path.basename(p_clean)
                                if (exe_name.lower() not in excluded_wine and
                                        not self._is_steam_runtime_or_tool(exe_name)):
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

        # CPU dynamic frequency in kilohertz range (.3f GHz)
        cpu_khz = self.get_cpu_freq_khz()
        cpu_ghz = cpu_khz / 1_000_000.0 if cpu_khz > 0 else 0.0
        cpu_str = self.cpu_model
        if cpu_ghz > 0.1:
            cpu_str = f"{self.cpu_model}@{cpu_ghz:.3f}GHz"

        # GPU dynamic clocks (Core with name, Memory with VRAM)
        core_khz, mem_khz = self.get_gpu_clocks_khz()
        core_ghz = core_khz / 1_000_000.0 if core_khz > 0 else 0.0
        mem_ghz = mem_khz / 1_000_000.0 if mem_khz > 0 else 0.0

        gpu_str = self.gpu_name
        if core_ghz > 0.05:
            gpu_str = f"{self.gpu_name}@{core_ghz:.3f}GHz"

        # Format: STAT|cpu_pct|cpu_temp|cpu_model|ram_used_gb|ram_total_gb|ram_pct|gpu_pct|gpu_temp|vram_used_gb|vram_total_gb|vram_pct|gpu_name|net_down_kb|net_up_kb|ip|net_link|disk_used_gb|disk_total_gb|disk_pct|cores_str|game_name|game_fps|gpu_mem_ghz|cpu_khz|gpu_core_khz|gpu_mem_khz
        return (f"STAT|{cpu_pct}|{cpu_temp}|{cpu_str}|"
                f"{ram_used:.1f}|{ram_total:.1f}|{ram_pct}|"
                f"{gpu_pct}|{gpu_temp}|{vram_used:.1f}|{vram_total:.1f}|{vram_pct}|"
                f"{gpu_str}|{down_kb:.1f}|{up_kb:.1f}|{self.ip}|{self.net_link}|"
                f"{disk_used:.1f}|{disk_total:.1f}|{disk_pct}|{cores_str}|"
                f"{game_name}|{game_fps}|{mem_ghz:.3f}|{cpu_khz}|{core_khz}|{mem_khz}\n")


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
        interval = float(cfg.get("interval", DEFAULT_INTERVAL))
    interval = max(0.0, min(10.0, float(interval)))

    collector = PcStatsCollector()
    rate_desc = "Uncapped (Zero Sleep / Maximum Speed)" if interval <= 0 else f"{interval}s ({1.0/interval:.1f} Hz)"
    log_info(f"Starting Audio Arch Telemetry Service (Target: {host}:{port}, Interval: {rate_desc})")
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

            # Clean pure-TCP loop: supports both On-Demand Call Mode (app requests packets) and Auto-Stream Mode
            mode_call = False
            while running:
                timeout = 0.5 if mode_call else (0.0 if interval <= 0 else interval)
                ready, _, _ = select.select([sock], [], [], timeout)
                if ready:
                    data = sock.recv(256)
                    if not data:
                        raise ConnectionResetError("Phone closed connection")
                    for line in data.decode("utf-8", "ignore").splitlines():
                        line = line.strip()
                        if line == "REQ" or line == "GET" or line == "POLL":
                            packet = collector.get_stat_packet()
                            sock.sendall(packet.encode("utf-8"))
                        elif line.startswith("MODE CALL") or line == "CALL":
                            mode_call = True
                            log_info("Switched to Call / On-Demand mode: phone asks for packets")
                        elif line.startswith("MODE STREAM") or line == "STREAM":
                            mode_call = False
                            log_info("Switched to Stream mode: PC pushes packets")
                        elif line.startswith("RATE ") or line.startswith("INTERVAL "):
                            try:
                                new_val = float(line.split()[1])
                                if new_val < 0:
                                    mode_call = True
                                    log_info("Switched to Call / On-Demand mode from phone")
                                elif new_val <= 0.01:
                                    mode_call = False
                                    interval = 0.0
                                    log_info("Updated telemetry rate: Uncapped (zero sleep, maximum speed)")
                                else:
                                    mode_call = False
                                    interval = max(0.01, min(10.0, new_val))
                                    log_info(f"Updated telemetry rate: {interval}s ({1.0/interval:.1f} Hz) from phone")
                            except Exception:
                                pass

                # If in Stream mode (not Call mode), push packets automatically
                if not mode_call:
                    t_cycle_start = time.perf_counter()
                    packet = collector.get_stat_packet()
                    sock.sendall(packet.encode("utf-8"))
                    if interval > 0:
                        elapsed = time.perf_counter() - t_cycle_start
                        sleep_sec = interval - elapsed
                        if sleep_sec > 0:
                            time.sleep(sleep_sec)

        except (socket.error, ConnectionRefusedError, BrokenPipeError, ConnectionResetError, socket.timeout):
            # When disconnected, ensure adb forward is active so reconnection succeeds immediately
            ensure_adb_stats_port(port)
            time.sleep(1.5)
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
    log_info("Configuring MangoHud for zero-disk-space RAM logging...")
    mhud_dir = os.path.expanduser("~/.config/MangoHud")
    mhud_conf = os.path.join(mhud_dir, "MangoHud.conf")
    os.makedirs(mhud_dir, exist_ok=True)

    # 1. Prepare in-RAM directory (/dev/shm/mangohud_logs) and symlink
    shm_log_dir = "/dev/shm/mangohud_logs"
    os.makedirs(shm_log_dir, exist_ok=True)
    local_log_dir = os.path.expanduser("~/.local/share/MangoHud/logs")
    os.makedirs(os.path.dirname(local_log_dir), exist_ok=True)
    try:
        if os.path.islink(local_log_dir):
            os.unlink(local_log_dir)
        elif os.path.isdir(local_log_dir):
            shutil.rmtree(local_log_dir)
        os.symlink(shm_log_dir, local_log_dir)
        log_ok(f"Symlinked {CYAN}{local_log_dir}{NC} -> {CYAN}{shm_log_dir}{NC} (100% RAM, zero disk writes)")
    except Exception as e:
        log_err(f"Symlink warning: {e}")

    # Clean up any leftover CSV files in home
    for f in glob.glob(os.path.expanduser("~/*_*.csv")) + glob.glob(os.path.expanduser("~/*_summary.csv")):
        try: os.remove(f)
        except Exception: pass

    # 2. Configure MangoHud.conf for invisible overlay & RAM logging
    existing = ""
    if os.path.exists(mhud_conf):
        with open(mhud_conf, "r") as f:
            existing = f.read()

    lines_to_set = [
        ("alpha", "0.0"),
        ("background_alpha", "0.0"),
        ("font_size", "1"),
        ("output_folder", local_log_dir),
        ("autostart_log", "1"),
        ("log_interval", "250"),
        ("log_duration", "0"),
        ("permit_upload", "0"),
    ]
    # Remove conflicting no_display (which disables auto-logging in MangoHud)
    lines = [l for l in existing.splitlines() if l.strip() != "no_display"]
    for key, val in lines_to_set:
        found = False
        new_line = f"{key}={val}"
        for i, l in enumerate(lines):
            l_strip = l.strip()
            if l_strip.startswith(key + "=") or l_strip == key:
                lines[i] = new_line
                found = True
                break
        if not found:
            lines.append(new_line)

    with open(mhud_conf, "w") as f:
        f.write("# Audio Arch Telemetry - Pure RAM Mode (Zero Disk Space)\n")
        f.write("\n".join(lines) + "\n")
    log_ok(f"Updated MangoHud config at: {CYAN}{mhud_conf}{NC}")

    # 3. Set up global session environment so ALL Steam games launch with MangoHud automatically
    env_dir = os.path.expanduser("~/.config/environment.d")
    env_conf = os.path.join(env_dir, "mangohud.conf")
    try:
        os.makedirs(env_dir, exist_ok=True)
        with open(env_conf, "w") as f:
            f.write("# Audio Arch - Global MangoHud Hook\nMANGOHUD=1\n")
        log_ok(f"Created global desktop session environment at: {CYAN}{env_conf}{NC}")
    except Exception as e:
        log_err(f"Could not write environment.d: {e}")

    log_ok("MangoHud pure-RAM logging is now active!")
    print()
    log_info("Key Advantages:")
    print(f"  \u2022 {BOLD}Zero Disk Usage:{NC} Stored purely in RAM ({CYAN}/dev/shm{NC}), preserving SSD lifespan.")
    print(f"  \u2022 {BOLD}Auto-Trimmed:{NC} File size is capped at 32 KB and deleted when the game ends.")
    print(f"  \u2022 {BOLD}100% Invisible Overlay:{NC} Zero on-screen clutter on your PC monitor.")
    print(f"  \u2022 {BOLD}Automatic:{NC} Starts logging immediately without pressing Shift+F2.")

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
