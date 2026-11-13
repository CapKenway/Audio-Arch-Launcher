# Audio Arch Launcher

[![Platform: Android](https://img.shields.io/badge/Platform-Android%204.0%2B%20(ICS%20--%2014%2B)-green.svg)](https://developer.android.com)
[![Host: Linux](https://img.shields.io/badge/Host-Linux%20(Arch%20%2F%20Debian%20%2F%20Fedora)-blue.svg)](https://kernel.org)
[![Audio: Bit-Perfect DAC](https://img.shields.io/badge/Audio-ALSA%20%7C%20AudioTrack%20(Zero--Jitter)-orange.svg)]()
[![Hardware Telemetry: Realtime](https://img.shields.io/badge/Telemetry-CPU%20%7C%20GPU%20%7C%20RAM%20%7C%20NET%20%7C%20FPS-cyan.svg)]()
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A specialized Android launcher that breathes new life into legacy and modern Android devices (supporting **Android 4.0 Ice Cream Sandwich / API 14** up to Android 14+). Turn any old phone into a dedicated **external USB DAC / audiophile sound card** and a real-time **hardware telemetry monitor & live gaming FPS dashboard** for your Linux PC.

---

## 🌟 Key Highlights

### 🖥️ 1. Real-Time PC Hardware Dashboard (Primary Tab)
- **Zero Scrolling Guarantee**: Optimized for compact and QVGA (240x320) screens in **Portrait, Landscape, and Reverse Landscape** (dock/stand friendly).
- **Per-Core CPU Monitoring**: Displays logical CPU thread utilization (e.g. Cores 1–12 on AMD Ryzen 5 5600) as individual bars alongside package temperatures.
- **RAM & Root Storage (`/`)**: Real-time memory utilization (Used / Total GB) and root disk filesystem usage.
- **Dedicated GPU Core & VRAM**: Core utilization, temperature, live VRAM usage, and exact GPU model identification (e.g. *Radeon RX 6700 XT*, *GeForce RTX 4080*).
- **Network Throughput & Link**: Live download and upload speeds, active host IP, and link type/speed (*Ethernet (1 Gbps)*, *WiFi: SSID (866 Mbps)*) without MAC truncation.
- **Dynamic Gaming FPS & Graph**:
  - Automatically hidden when no game is running, allowing the network stats to cleanly fill the card.
  - Automatically activates when a game launches: shows game title, live FPS counter, and a smooth real-time Bezier FPS curve (`FpsGraphView`) with a 60 FPS reference guide.
- **Low Overhead**: Zero runtime allocations and pure TCP streaming over ADB tunnel (`tcp:12347`), ensuring zero audio clicks or underruns.

### 🎵 2. External USB Sound Card & DAC Controller
- **Dual Daemon Management**: Control both Native C ALSA daemon (direct hardware DAC via 3.5mm jack) and Java AudioTrack daemon (Bluetooth & system routing) directly on device.
- **Persistent Root Session**: Single Superuser prompt upon startup; zero subsequent root permission spam.
- **Bluetooth Hub**: Scan, pair, and route audio to Bluetooth earbuds/headphones (Redmi Buds, AirPods, Galaxy Buds, etc.) directly from the launcher.
- **Volume & Latency Control**: Real-time hardware volume slider synced with physical phone buttons.

### 📱 3. Appliance Launcher & Offline Music Player
- **Home Replacement (`CATEGORY_HOME`)**: Replaces standard stock home screens, turning the device into a dedicated desktop dock or appliance upon boot.
- **Immersive Fullscreen**: Hides the status and navigation bar across all Android versions (API 14+) for a clean aesthetic.
- **Swipe-Up Navigation**: Bottom navigation bar tucks away automatically; swipe up or tap the indicator pill to switch tabs.
- **Standalone Music Gallery**: Built-in offline audio player scanning local storage via `MediaStore` with album art caching and background playback service.
- **Display Orientation Settings**: Choose Portrait, Landscape, Reverse Landscape (ideal when the USB cable points upward or into a dock), or Sensor Auto-rotation.

---

## 🏗️ Architecture

```
+──────────────────────────────────────────────────────────────────────────+
|                            HOST PC (Linux)                               |
|                                                                          |
|  /proc/stat + /proc/meminfo + sysfs + MangoHud / Gamescope / IPC + PipeWire |
|                                     │                                    |
|                                     ▼                                    |
|                   audioarch-stats.service (stat_sender.py)               |
|                                     │                                    |
|                      tcp:127.0.0.1:12347 (ADB Forward)                   |
+─────────────────────────────────────┼────────────────────────────────────+
                                      │  Physical USB (or WiFi)
                                      ▼
+──────────────────────────────────────────────────────────────────────────+
|                    ANDROID PHONE (Audio Arch Launcher)                   |
|                                                                          |
|   PcStatsServer (Listening on 0.0.0.0:12347, Fast delimited parsing)     |
|                                     │                                    |
|   Top-Right Chip: [Daemon Status & Version: AudioTrack v2.0]             |
|                                                                          |
|   StatsFragment (Primary Tab, Non-Scrolling 2x2 Grid):                   |
|   ┌───────────────────────────────┬──────────────────────────────────┐   |
|   │ Card 1: CPU + Temp + Cores    │ Card 2: RAM Bar & DISK/Root Bar  │   |
|   ├───────────────────────────────┼──────────────────────────────────┤   |
|   │ Card 3: GPU Core & VRAM Bar   │ Card 4: NET Speeds & Dynamic GAME│   |
|   │         Radeon RX 6700 XT     │         Cyberpunk 2077 | 118 FPS │   |
|   │                               │         [ Live FPS Graph ~~~~ ]  │   |
|   └───────────────────────────────┴──────────────────────────────────┘   |
+──────────────────────────────────────────────────────────────────────────+
```

---

## 📊 Telemetry Packet Protocol

Transmission uses a single-line, pipe-delimited format terminated by a newline:

```
STAT|cpu_pct|cpu_temp|cpu_model|ram_used_gb|ram_total_gb|ram_pct|gpu_pct|gpu_temp|vram_used_gb|vram_total_gb|vram_pct|gpu_name|net_down_kb|net_up_kb|ip|net_link|disk_used_gb|disk_total_gb|disk_pct|cores_str|game_name|game_fps\n
```

### Protocol Fields (22 Fields)
| # | Field | Type | Description | Example |
|---|---|---|---|---|
| 0 | `STAT` | Literal | Header tag | `STAT` |
| 1 | `cpu_pct` | Int (0-100) | Overall CPU usage | `7` |
| 2 | `cpu_temp` | Int | CPU temperature in °C | `25` |
| 3 | `cpu_model` | String | CPU model string | `AMD Ryzen 5 5600 6-Core` |
| 4 | `ram_used_gb` | Float | Used RAM in GB | `13.4` |
| 5 | `ram_total_gb` | Float | Total installed RAM | `31.2` |
| 6 | `ram_pct` | Int (0-100) | RAM percentage | `42` |
| 7 | `gpu_pct` | Int (0-100) | GPU core percentage | `2` |
| 8 | `gpu_temp` | Int | GPU temperature in °C | `46` |
| 9 | `vram_used_gb` | Float | Used VRAM in GB | `1.4` |
| 10 | `vram_total_gb` | Float | Total VRAM in GB | `12.0` |
| 11 | `vram_pct` | Int (0-100) | VRAM percentage | `11` |
| 12 | `gpu_name` | String | Exact GPU model | `Radeon RX 6700 XT` |
| 13 | `net_down_kb` | Float | Download speed in KB/s | `1024.5` |
| 14 | `net_up_kb` | Float | Upload speed in KB/s | `120.0` |
| 15 | `ip` | String | Host active IP | `192.168.10.114` |
| 16 | `net_link` | String | Network interface & speed | `Ethernet (1 Gbps)` |
| 17 | `disk_used_gb` | Float | Root `/` used space | `71.2` |
| 18 | `disk_total_gb` | Float | Root `/` total capacity | `117.3` |
| 19 | `disk_pct` | Int (0-100) | Root filesystem usage % | `60` |
| 20 | `cores_str` | CSV String | Per-thread CPU percentages | `16,15,5,4,7,2,9,6,9,4,5,3` |
| 21 | `game_name` | String | Active game title (or `No Game`) | `Cyberpunk 2077` |
| 22 | `game_fps` | Int | Real-time game FPS | `118` |

---

## 🚀 Getting Started

### 1. Build and Install the Android App
From this repository:
```bash
# Build the APK:
./gradlew assembleDebug

# Install via ADB to your device:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
*Tip: On the phone, press **Home** and set **Audio Arch** as "Always" to use it as your dedicated launcher.*

### 2. Configure Host Telemetry (`stat_sender.py`)
The included Python script handles sensor collection and installs as an unprivileged systemd user service:

```bash
# Install and activate the background service:
python3 stat_sender.py install

# Check status and test live sensor packet:
python3 stat_sender.py status

# Adjust polling interval (e.g. 0.5s = 2.0 Hz, 1.0s = 1.0 Hz):
python3 stat_sender.py interval 0.5
python3 stat_sender.py interval       # Show current interval

# Enable automatic gaming FPS tracking via MangoHud:
python3 stat_sender.py setup-mangohud

# Or feed FPS manually / from any custom launcher or script:
python3 stat_sender.py set-fps "Elden Ring" 120
# Or directly pipe into IPC:
echo "Elden Ring|120" > /dev/shm/game_fps
```

### 3. Service Management
```bash
python3 stat_sender.py stop
python3 stat_sender.py start
python3 stat_sender.py restart
python3 stat_sender.py uninstall
```

---

## 📱 Tested Devices & Hardware
- **Target Device**: Samsung Galaxy Star Duos (GT-S5282)
- **OS**: Android 4.4.4 KitKat (API 19) / Android 4.1.2 Jelly Bean (API 16)
- **Specs**: Single-Core 1.0 GHz Cortex-A5, 512MB RAM, 240x320 QVGA Display (ldpi)
- **Host System**: Arch Linux (Kernel 6.x / 7.x-zen), AMD Ryzen 5 5600, AMD Radeon RX 6700 XT, PipeWire / WirePlumber

---

## 📄 License
This project is licensed under the [MIT License](LICENSE).
