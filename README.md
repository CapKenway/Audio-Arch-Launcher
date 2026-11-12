# Audio Arch Launcher

A specialized Android launcher application designed to convert legacy and modern Android devices (supporting **Android 4.0 Ice Cream Sandwich / API 14** up to modern Android) into a dedicated external USB sound card and standalone MP3 player.

---

## Highlights

- **Launcher Replacement (`CATEGORY_HOME`)**: Replaces standard stock home screens, turning the device upon boot into an audio appliance with no distraction.
- **Immersive Fullscreen (No Status / Notification Bar)**: Automatically hides the notification bar across all Android versions (API 14+) for a distraction-free external sound card & player interface.
- **Non-Swipeable ViewPager**: Horizontal page swiping is disabled to avoid accidental navigation when adjusting the volume slider or scrolling lists.
- **Swipe-Up Gesture Navigation**: The bottom navigation row and Now Playing bar tuck away automatically. A simple swipe up from the bottom edge (or tapping the discreet bottom handle pill) smoothly brings up the navigation bar.
- **Android 4.0+ ICS Compatibility (API 14+)**: Tested against ultra low-spec hardware (single-core ARMv7, 512MB RAM, KitKat 4.4 / ICS 4.0) while delivering a modern Material Dark UI via AndroidX AppCompat and vector drawables backport.
- **Dual Daemon Management**: Single dynamic Start/Stop toggle button for each daemon (Native C ALSA and Java AudioTrack) backed by a persistent root shell session that asks for Superuser permission only once on launch.
- **Bluetooth Control Hub**: Built-in Bluetooth device manager allowing scanning, pairing, and selecting Bluetooth earbuds/headphones (Redmi Buds, AirPods, Galaxy Buds, etc.) directly on device.
- **Audio & Volume Control**: Real-time volume slider synced with physical volume buttons, route indicator (Bluetooth / Headphones / Speaker).
- **Standalone Music Gallery**: Built-in MP3/audio player scanning local storage via `MediaStore`, featuring Songs list, Albums grid with album art downsampling, and persistent Now Playing bottom bar with background `MusicPlayerService`.

---

## Architecture & Layout

```
Audio Arch Launcher/
├── icon.svg                                 # Scalable vector app icon (SVG)
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml              # Home launcher & permissions configuration
│   │   ├── java/com/audioarch/launcher/
│   │   │   ├── LauncherActivity.java        # Main launcher activity with ViewPager + BottomNav
│   │   │   ├── fragments/
│   │   │   │   ├── DashboardFragment.java   # Daemon status cards, volume, Bluetooth dialog
│   │   │   │   ├── MusicFragment.java       # Songs & Albums tabbed music browser
│   │   │   │   └── SettingsFragment.java    # Port, sample rate, routing & auto-start settings
│   │   │   ├── services/
│   │   │   │   └── MusicPlayerService.java  # Background MediaPlayer service with notifications
│   │   │   ├── adapters/
│   │   │   │   ├── SongAdapter.java         # RecyclerView adapter with async album art loader
│   │   │   │   └── AlbumAdapter.java        # Grid adapter for album collections
│   │   │   ├── models/
│   │   │   │   ├── Song.java                # Song metadata model
│   │   │   │   └── DaemonStatus.java        # Process status model
│   │   │   └── utils/
│   │   │       ├── DaemonController.java    # Shell ps parser, start/stop daemons via su/sh
│   │   │       ├── BluetoothHelper.java     # BluetoothAdapter & A2DP discovery/pairing
│   │   │       └── AudioController.java     # AudioManager volume & route management
│   │   └── res/
│   │       ├── drawable/                    # Vector icons, shape cards, status indicators
│   │       ├── layout/                      # Dark theme layouts with Material cards
│   │       ├── menu/                        # Bottom navigation menu
│   │       └── values/                      # Colors (Cyan/Green Audio Arch theme), strings, styles
│   └── build.gradle                         # MinSdk 14, AndroidX pinned dependencies
├── build.gradle                             # Project-level buildscript
├── settings.gradle
├── gradle.properties
└── gradlew                                  # Gradle 7.5 wrapper
```

---

## Screen Overview

### 1. Dashboard Tab
- **Header**: Audio Arch branding in cyan/green matching the CLI.
- **ALSA Daemon (Native C)**: Card showing live running state (green/red indicator) and PID. Start/Stop buttons targeting `/data/local/tmp/audioarch_daemon`.
- **AudioTrack Daemon (Java)**: Card showing live running state for Dalvik `app_process` receiver (`com.audioarch.AudioReceiver`).
- **Volume**: Styled SeekBar linked to `AudioManager.STREAM_MUSIC` with live percentage display.
- **Bluetooth**: Connected device indicator + "Select Device" dialog showing paired devices and live scanning for available devices.

### 2. Music Gallery Tab
- **Tab Layout**: Switch smoothly between "Songs" and "Albums".
- **Songs List**: Shows title, artist, album, duration, and dynamically loaded album art thumbnail. Tapping any song begins playback and updates the Now Playing bar.
- **Albums Grid**: 2-column grid showing album covers, titles, and artists. Tapping an album filters the songs list.
- **Now Playing Bar**: Docked at the bottom across all tabs with marquee title, album art, play/pause toggle, next, and previous buttons.

### 3. Settings Tab
- **TCP Port**: Configurable ADB forward port (default `12345`).
- **Sample Rate**: Select between `44100 Hz`, `48000 Hz`, and `22050 Hz`.
- **Audio Backend**: Auto-detect, ALSA (3.5mm jack / speaker), or Bluetooth.
- **Auto-start**: Toggle auto-launch of the preferred daemon when launcher initializes.

---

## Building and Installing

### 1. Build APK
From this project directory:
```bash
./gradlew assembleDebug
```
or for an optimized release build:
```bash
./gradlew assembleRelease
```

The APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### 2. Install to Device via ADB
Connect your Android phone via USB:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. Set as Default Launcher
1. Press the **Home** button on the phone.
2. Android will prompt: **Select a home app**.
3. Choose **Audio Arch** and select **Always**.

### 4. Running alongside Host Stream
Launch your audio streaming script on your Arch Linux computer:
```bash
cd "/home/kenway/Projects/Audio Arch"
./audio_arch.sh
```
The Audio Arch Launcher on your phone will immediately reflect the active daemon status (green running indicator) and allow you to adjust the volume or switch Bluetooth earphones on the fly.

