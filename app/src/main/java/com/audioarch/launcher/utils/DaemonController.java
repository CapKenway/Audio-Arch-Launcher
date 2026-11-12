package com.audioarch.launcher.utils;

import android.content.Context;
import android.util.Log;
import com.audioarch.launcher.models.DaemonStatus;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Controller for the standalone AudioArch background daemons:
 *   1. Native C ALSA DAC Daemon: /data/local/tmp/audioarch_daemon
 *   2. Java AudioTrack Daemon:   app_process ... com.audioarch.AudioReceiver
 *
 * Designed to be 100% compatible with the host audio_arch.sh script.
 * Operates independently of the app; the app acts as a dashboard extension.
 * Never kills the launcher app itself and never blocks the UI thread.
 */
public class DaemonController {

    private static final String TAG = "DaemonController";

    public static final String C_BIN = "audioarch_daemon";
    public static final String C_BIN_SHORT = "audioarch_daem"; // 15-char kernel comm truncation
    public static final String BT_PROCESS_NAME = "audioarch_bt_daemon";
    public static final String BT_PROCESS_SHORT = "audioarch_bt_d";
    public static final String BT_CLASS_NAME = "AudioReceiver";

    public static final String REMOTE_C_BIN = "/data/local/tmp/audioarch_daemon";
    public static final String REMOTE_BT_JAR = "/data/local/tmp/audio_receiver.jar";
    public static final String REMOTE_CACHE = "/data/local/tmp/dalvik-cache";
    public static final String REMOTE_C_LOG = "/data/local/tmp/audioarch_c.log";
    public static final String REMOTE_BT_LOG = "/data/local/tmp/audio_receiver.log";
    public static final String REMOTE_VER_FILE = "/data/local/tmp/audioarch_version";
    public static final String DEFAULT_DAEMON_VERSION = "1.2.0";

    public static final int DEFAULT_PORT = 12345;
    public static final int DEFAULT_SAMPLE_RATE = 44100;

    private static final ExecutorService EXEC_POOL = Executors.newCachedThreadPool();

    public DaemonController() {
    }

    /**
     * Reads the deployed daemon version from /data/local/tmp/audioarch_version.
     * Falls back to DEFAULT_DAEMON_VERSION (1.2.0) if unreadable.
     */
    public String getDaemonVersion() {
        File f = new File(REMOTE_VER_FILE);
        if (f.canRead()) {
            BufferedReader br = null;
            try {
                br = new BufferedReader(new FileReader(f));
                String line = br.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    return line.trim();
                }
            } catch (Throwable ignored) {
            } finally {
                if (br != null) try { br.close(); } catch (Throwable ignored) {}
            }
        }
        return DEFAULT_DAEMON_VERSION;
    }

    /**
     * Executes a command via root (su -c) or standard shell (sh -c).
     * Runs with a strict timeout and drains process streams so it never hangs or blocks.
     */
    public boolean runCommand(final String cmd) {
        Future<Boolean> future = EXEC_POOL.submit(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                // Try root first
                Process p = null;
                try {
                    p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                    drainProcess(p);
                    int code = p.waitFor();
                    if (code == 0) return true;
                } catch (Throwable ignored) {
                } finally {
                    if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
                }

                // Fallback to standard sh
                try {
                    p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                    drainProcess(p);
                    p.waitFor();
                    return true;
                } catch (Throwable ignored) {
                    return false;
                } finally {
                    if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
                }
            }
        });

        try {
            return future.get(2000, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            future.cancel(true);
            return false;
        }
    }

    private static void drainProcess(final Process p) {
        if (p == null) return;
        try {
            // Close stdin so child process doesn't wait for input
            p.getOutputStream().close();
        } catch (Throwable ignored) {}

        EXEC_POOL.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream in = p.getInputStream();
                    byte[] buf = new byte[256];
                    while (in.read(buf) != -1) {}
                    in.close();
                } catch (Throwable ignored) {}
            }
        });
        EXEC_POOL.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream err = p.getErrorStream();
                    byte[] buf = new byte[256];
                    while (err.read(buf) != -1) {}
                    err.close();
                } catch (Throwable ignored) {}
            }
        });
    }

    /**
     * Starts the Native C ALSA daemon smoothly as a standalone OS subprocess.
     * Compatible with audio_arch.sh script.
     */
    public boolean startCDaemon(Context context, int port, int sampleRate) {
        // Stop any running C daemon first (NEVER match com.audioarch.launcher!)
        stopCDaemon();

        // Ensure binary exists in /data/local/tmp (extract from assets if missing)
        ensureAssetExtracted(context, "audioarch_daemon", REMOTE_C_BIN);

        // Keep device awake over USB
        runCommand("svc power stayon usb 2>/dev/null || true");

        // Start native ALSA daemon in background.
        // Uses subshell (...) & so it detaches cleanly without needing nohup!
        String cmd = "chmod 755 " + REMOTE_C_BIN + " && (" + REMOTE_C_BIN + " " + port + " " + sampleRate + " 0 0 > " + REMOTE_C_LOG + " 2>&1 &)";
        runCommand(cmd);

        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        return checkCDaemon(port).isRunning();
    }

    /**
     * Stops the Native C ALSA daemon smoothly.
     * Explicitly targets only audioarch_daemon, NEVER com.audioarch.launcher!
     */
    public boolean stopCDaemon() {
        runCommand("killall -9 " + C_BIN + " || pkill -9 -x " + C_BIN + " || pkill -9 -x " + C_BIN_SHORT + " || true");
        try { Thread.sleep(150); } catch (InterruptedException ignored) {}
        return true;
    }

    /**
     * Starts the Java AudioTrack daemon as a standalone OS subprocess via app_process.
     * Exactly identical to what audio_arch.sh executes.
     */
    public boolean startBtDaemon(Context context, int port, int sampleRate) {
        // Stop any running instance first
        stopBtDaemon();

        // Ensure JAR and dalvik-cache directory exist
        ensureAssetExtracted(context, "audio_receiver.jar", REMOTE_BT_JAR);
        runCommand("mkdir -p " + REMOTE_CACHE + " && chmod 777 " + REMOTE_CACHE + " || true");
        runCommand("svc power stayon usb 2>/dev/null || true");

        // Start via app_process in background.
        // Uses subshell (...) & so it detaches cleanly without needing nohup!
        String cmd = "export ANDROID_DATA=/data/local/tmp; " +
                "export CLASSPATH=" + REMOTE_BT_JAR + "; " +
                "(app_process /system/bin --nice-name=" + BT_PROCESS_NAME +
                " com.audioarch.AudioReceiver " + port + " " + sampleRate +
                " > " + REMOTE_BT_LOG + " 2>&1 &)";
        runCommand(cmd);

        try { Thread.sleep(350); } catch (InterruptedException ignored) {}
        return checkBtDaemon(port).isRunning();
    }

    /**
     * Stops the Java AudioTrack daemon smoothly.
     * Targets only com.audioarch.AudioReceiver and audioarch_bt_daemon, NEVER com.audioarch.launcher!
     */
    public boolean stopBtDaemon() {
        runCommand("pkill -9 -f 'com.audioarch.AudioReceiver' || pkill -9 -x " + BT_PROCESS_NAME +
                " || pkill -9 -x " + BT_PROCESS_SHORT + " || killall -9 " + BT_PROCESS_NAME + " || true");
        try { Thread.sleep(150); } catch (InterruptedException ignored) {}
        return true;
    }

    /**
     * Stops all AudioArch standalone daemons cleanly without killing the launcher app.
     */
    public boolean stopAllDaemons() {
        runCommand("killall -9 " + C_BIN + " || pkill -9 -x " + C_BIN + " || pkill -9 -x " + C_BIN_SHORT +
                " || pkill -9 -f 'com.audioarch.AudioReceiver' || pkill -9 -x " + BT_PROCESS_NAME +
                " || pkill -9 -x " + BT_PROCESS_SHORT + " || true");
        try { Thread.sleep(150); } catch (InterruptedException ignored) {}
        return true;
    }

    /**
     * Checks if the Native C ALSA daemon is running.
     * Uses passive /proc inspection and port verification - NEVER prompts for root!
     */
    public DaemonStatus checkCDaemon(int port) {
        // Method 1: Check /proc filesystem
        int pid = findPidInProc(C_BIN, C_BIN_SHORT);
        if (pid > 0) {
            return new DaemonStatus("ALSA Daemon", true, "alsa", "", pid);
        }

        // Method 2: Shell ps check
        pid = findPidViaPs(C_BIN, C_BIN_SHORT);
        if (pid > 0) {
            return new DaemonStatus("ALSA Daemon", true, "alsa", "", pid);
        }

        // Method 3: Port check + log check fallback
        if (isPortActive(port)) {
            if (isLogActive(REMOTE_C_LOG, REMOTE_BT_LOG)) {
                return new DaemonStatus("ALSA Daemon", true, "alsa", "", -1);
            }
        }

        return DaemonStatus.stopped("ALSA Daemon", "alsa");
    }

    /**
     * Checks if the Java AudioTrack daemon is running.
     * Uses passive /proc inspection and port verification - NEVER prompts for root!
     */
    public DaemonStatus checkBtDaemon(int port) {
        // Method 1: Check /proc filesystem
        int pid = findPidInProc(BT_PROCESS_NAME, BT_PROCESS_SHORT, BT_CLASS_NAME);
        if (pid > 0) {
            return new DaemonStatus("AudioTrack Daemon", true, "bt", "", pid);
        }

        // Method 2: Shell ps check
        pid = findPidViaPs(BT_PROCESS_NAME, BT_PROCESS_SHORT, BT_CLASS_NAME);
        if (pid > 0) {
            return new DaemonStatus("AudioTrack Daemon", true, "bt", "", pid);
        }

        // Method 3: Port check + log check fallback
        if (isPortActive(port)) {
            if (isLogActive(REMOTE_BT_LOG, REMOTE_C_LOG)) {
                return new DaemonStatus("AudioTrack Daemon", true, "bt", "", -1);
            }
        }

        return DaemonStatus.stopped("AudioTrack Daemon", "bt");
    }

    /**
     * Extracts an asset file to destination path if not already present.
     * Ensures the standalone binaries exist on the device even before the host PC connects.
     */
    public void ensureAssetExtracted(Context context, String assetName, String destPath) {
        if (context == null) return;
        try {
            File dest = new File(destPath);
            if (dest.exists() && dest.length() > 0) return;

            // Extract to app internal files dir
            File internalFile = new File(context.getFilesDir(), assetName);
            InputStream in = context.getAssets().open(assetName);
            FileOutputStream out = new FileOutputStream(internalFile);
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            in.close();
            out.close();

            try {
                internalFile.setReadable(true, false);
                internalFile.setExecutable(true, false);
            } catch (Throwable ignored) {}

            // Copy to /data/local/tmp via root/shell so standalone daemons and ADB can run it
            String copyCmd = "mkdir -p /data/local/tmp && cp " + internalFile.getAbsolutePath() + " " + destPath +
                    " && chmod 755 " + destPath +
                    " && (echo '" + DEFAULT_DAEMON_VERSION + "' > " + REMOTE_VER_FILE + " && chmod 666 " + REMOTE_VER_FILE + " || true)";
            runCommand(copyCmd);
            Log.i(TAG, "Deployed asset " + assetName + " -> " + destPath);
        } catch (Throwable t) {
            Log.w(TAG, "Asset deployment warning for " + assetName + ": " + t.getMessage());
        }
    }

    /**
     * Scans /proc for processes matching target names. Completely passive, requires no root.
     */
    private int findPidInProc(String... targets) {
        try {
            File procDir = new File("/proc");
            File[] files = procDir.listFiles();
            if (files == null) return -1;

            for (File file : files) {
                if (!file.isDirectory()) continue;
                String name = file.getName();
                if (!isNumeric(name)) continue;

                // Check cmdline
                File cmdline = new File(file, "cmdline");
                if (cmdline.canRead()) {
                    FileInputStream fis = null;
                    try {
                        fis = new FileInputStream(cmdline);
                        byte[] buffer = new byte[256];
                        int bytesRead = fis.read(buffer);
                        if (bytesRead > 0) {
                            String cmd = new String(buffer, 0, bytesRead);
                            // Avoid matching launcher app itself
                            if (cmd.contains("com.audioarch.launcher")) {
                                continue;
                            }
                            for (String target : targets) {
                                if (cmd.contains(target)) {
                                    return Integer.parseInt(name);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (fis != null) try { fis.close(); } catch (Throwable ignored) {}
                    }
                }

                // Check comm
                File comm = new File(file, "comm");
                if (comm.canRead()) {
                    BufferedReader br = null;
                    try {
                        br = new BufferedReader(new FileReader(comm));
                        String line = br.readLine();
                        if (line != null) {
                            for (String target : targets) {
                                if (line.contains(target)) {
                                    return Integer.parseInt(name);
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (br != null) try { br.close(); } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private int findPidViaPs(String... targets) {
        String psOutput = execCommandWithTimeout("ps", 600);
        if (psOutput == null || psOutput.isEmpty()) return -1;

        String[] lines = psOutput.split("\n");
        for (String line : lines) {
            // Never match the main launcher activity process
            if (line.contains("com.audioarch.launcher")) {
                continue;
            }
            for (String target : targets) {
                if (line.contains(target)) {
                    try {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length > 1) {
                            return Integer.parseInt(parts[1]);
                        }
                    } catch (Throwable ignored) {}
                    return 1;
                }
            }
        }
        return -1;
    }

    public boolean isPortActive(int port) {
        String portHex = String.format(Locale.US, ":%04X", port);
        return checkProcNetTcp("/proc/net/tcp", portHex) ||
               checkProcNetTcp("/proc/net/tcp6", portHex);
    }

    private boolean checkProcNetTcp(String path, String portHex) {
        File file = new File(path);
        if (!file.canRead()) return false;

        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(file));
            String line;
            boolean firstLine = true;
            while ((line = br.readLine()) != null) {
                if (firstLine) { firstLine = false; continue; }
                String trimmed = line.trim();
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 4) {
                    String localAddress = parts[1];
                    String state = parts[3];
                    if (localAddress.endsWith(portHex)) {
                        if ("0A".equalsIgnoreCase(state) || "01".equalsIgnoreCase(state)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (br != null) try { br.close(); } catch (Throwable ignored) {}
        }
        return false;
    }

    private boolean isLogActive(String primaryLog, String secondaryLog) {
        try {
            File p = new File(primaryLog);
            if (p.exists() && p.length() > 0) {
                File s = new File(secondaryLog);
                if (!s.exists()) return true;
                return p.lastModified() >= s.lastModified();
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public String readLog(String logPath) {
        String output = execCommandWithTimeout("tail -n 20 " + logPath, 600);
        return output != null ? output : "No log data available";
    }

    private String execCommandWithTimeout(final String cmd, long timeoutMs) {
        Future<String> future = EXEC_POOL.submit(new Callable<String>() {
            @Override
            public String call() {
                Process process = null;
                try {
                    process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                    drainProcess(process);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                    reader.close();
                    process.waitFor();
                    return sb.toString();
                } catch (Throwable t) {
                    return null;
                } finally {
                    if (process != null) {
                        try { process.destroy(); } catch (Throwable ignored) {}
                    }
                }
            }
        });

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            future.cancel(true);
            return null;
        }
    }

    private static boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) return false;
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isDigit(str.charAt(i))) return false;
        }
        return true;
    }
}
