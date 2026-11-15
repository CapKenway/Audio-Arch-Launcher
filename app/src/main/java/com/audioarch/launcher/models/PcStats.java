package com.audioarch.launcher.models;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

public class PcStats {
    private int cpuPct;
    private int cpuTemp;
    private String cpuModel;
    private int[] cpuCorePcts;
    private float ramUsedGb;
    private float ramTotalGb;
    private int ramPct;
    private int gpuPct;
    private int gpuTemp;
    private float vramUsedGb;
    private float vramTotalGb;
    private int vramPct;
    private float gpuMemGhz;
    private long cpuKhz;
    private long gpuCoreKhz;
    private long gpuMemKhz;
    private String cpuBaseModel;
    private String gpuBaseName;
    private String gpuName;
    private float netDownKbps;
    private float netUpKbps;
    private String netIp;
    private String netMac;
    private String netLink;
    private float diskUsedGb;
    private float diskTotalGb;
    private int diskPct;
    private String gameName;
    private int gameFps;
    private long timestamp;

    public PcStats() {
        this.cpuPct = 0;
        this.cpuTemp = -1;
        this.cpuModel = "PC CPU";
        this.cpuCorePcts = null;
        this.ramUsedGb = 0f;
        this.ramTotalGb = 0f;
        this.ramPct = 0;
        this.gpuPct = 0;
        this.gpuTemp = -1;
        this.vramUsedGb = 0f;
        this.vramTotalGb = 0f;
        this.vramPct = 0;
        this.gpuMemGhz = 0f;
        this.cpuKhz = 0L;
        this.gpuCoreKhz = 0L;
        this.gpuMemKhz = 0L;
        this.cpuBaseModel = "CPU";
        this.gpuBaseName = "GPU";
        this.gpuName = "PC GPU";
        this.netDownKbps = 0f;
        this.netUpKbps = 0f;
        this.netIp = "";
        this.netMac = "";
        this.netLink = "";
        this.diskUsedGb = 0f;
        this.diskTotalGb = 0f;
        this.diskPct = 0;
        this.gameName = "No Game";
        this.gameFps = 0;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Fast parser for delimited format:
     * STAT|cpu_pct|cpu_temp|cpu_model|ram_used_gb|ram_total_gb|ram_pct|gpu_pct|gpu_temp|vram_used_gb|vram_total_gb|vram_pct|gpu_name|net_down_kb|net_up_kb|ip|mac|disk_used_gb|disk_total_gb|disk_pct|cores_str|game_name|game_fps
     */
    public static PcStats parseDelimited(String line) {
        if (line == null || !line.startsWith("STAT|")) return null;
        String[] parts = line.split("\\|", -1);
        if (parts.length < 12) return null;
        PcStats s = new PcStats();
        try {
            s.cpuPct = clampPct(parseInt(parts[1], 0));
            s.cpuTemp = parseInt(parts[2], -1);
            s.cpuModel = parts[3].trim().isEmpty() ? "CPU" : parts[3].trim();
            s.ramUsedGb = parseFloat(parts[4], 0f);
            s.ramTotalGb = parseFloat(parts[5], 0f);
            s.ramPct = clampPct(parseInt(parts[6], 0));
            s.gpuPct = clampPct(parseInt(parts[7], 0));
            s.gpuTemp = parseInt(parts[8], -1);
            s.vramUsedGb = parseFloat(parts[9], 0f);
            s.vramTotalGb = parseFloat(parts[10], 0f);
            s.vramPct = clampPct(parseInt(parts[11], 0));
            if (parts.length > 12) s.gpuName = parts[12].trim().isEmpty() ? "GPU" : parts[12].trim();
            if (parts.length > 13) s.netDownKbps = parseFloat(parts[13], 0f);
            if (parts.length > 14) s.netUpKbps = parseFloat(parts[14], 0f);
            if (parts.length > 15) s.netIp = parts[15].trim();
            if (parts.length > 16) {
                s.netLink = parts[16].trim();
                s.netMac = s.netLink;
            }
            if (parts.length > 17) s.diskUsedGb = parseFloat(parts[17], 0f);
            if (parts.length > 18) s.diskTotalGb = parseFloat(parts[18], 0f);
            if (parts.length > 19) s.diskPct = clampPct(parseInt(parts[19], 0));
            if (parts.length > 20 && !parts[20].trim().isEmpty()) {
                String[] coreStrs = parts[20].trim().split(",");
                s.cpuCorePcts = new int[coreStrs.length];
                for (int i = 0; i < coreStrs.length; i++) {
                    s.cpuCorePcts[i] = clampPct(parseInt(coreStrs[i], 0));
                }
            }
            if (parts.length > 21) {
                String g = parts[21].trim();
                s.gameName = g.isEmpty() ? "No Game" : g;
            }
            if (parts.length > 22) {
                s.gameFps = parseInt(parts[22], 0);
            }
            if (parts.length > 23) {
                float v23 = parseFloat(parts[23], 0f);
                if (v23 > 10000f) {
                    s.gpuMemKhz = (long) v23;
                    s.gpuMemGhz = s.gpuMemKhz / 1_000_000f;
                } else {
                    s.gpuMemGhz = v23;
                    s.gpuMemKhz = (long) (v23 * 1_000_000f);
                }
            }
            if (parts.length > 24) {
                s.cpuKhz = parseLong(parts[24], 0L);
            }
            if (parts.length > 25) {
                s.gpuCoreKhz = parseLong(parts[25], 0L);
            }
            if (parts.length > 26) {
                long mKhz = parseLong(parts[26], 0L);
                if (mKhz > 0) {
                    s.gpuMemKhz = mKhz;
                    s.gpuMemGhz = mKhz / 1_000_000f;
                }
            }

            // Extract base names and fallback clocks from formatted strings if not set
            if (s.cpuModel.contains("@")) {
                String[] p = s.cpuModel.split("@", 2);
                s.cpuBaseModel = p[0].trim();
                if (s.cpuKhz <= 0 && p.length > 1) {
                    String num = p[1].replace("GHz", "").replace("ghz", "").trim();
                    s.cpuKhz = (long) (parseFloat(num, 0f) * 1_000_000f);
                }
            } else {
                s.cpuBaseModel = s.cpuModel;
            }

            if (s.gpuName.contains("@")) {
                String[] p = s.gpuName.split("@", 2);
                s.gpuBaseName = p[0].trim();
                if (s.gpuCoreKhz <= 0 && p.length > 1) {
                    String num = p[1].replace("GHz", "").replace("ghz", "").trim();
                    s.gpuCoreKhz = (long) (parseFloat(num, 0f) * 1_000_000f);
                }
            } else {
                s.gpuBaseName = s.gpuName;
            }
            s.timestamp = System.currentTimeMillis();
            return s;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static PcStats parseJson(String json) {
        if (json == null || !json.startsWith("{")) return null;
        try {
            JSONObject obj = new JSONObject(json);
            PcStats s = new PcStats();
            s.cpuPct = clampPct(obj.optInt("cpu_pct", 0));
            s.cpuTemp = obj.optInt("cpu_temp", -1);
            s.cpuModel = obj.optString("cpu_model", obj.optString("cpu_name", "CPU"));
            s.ramUsedGb = (float) obj.optDouble("ram_used_gb", 0.0);
            s.ramTotalGb = (float) obj.optDouble("ram_total_gb", 0.0);
            s.ramPct = clampPct(obj.optInt("ram_pct", 0));
            s.gpuPct = clampPct(obj.optInt("gpu_pct", 0));
            s.gpuTemp = obj.optInt("gpu_temp", -1);
            s.vramUsedGb = (float) obj.optDouble("vram_used_gb", 0.0);
            s.vramTotalGb = (float) obj.optDouble("vram_total_gb", 0.0);
            s.vramPct = clampPct(obj.optInt("vram_pct", 0));
            s.gpuName = obj.optString("gpu_name", "GPU");
            s.netDownKbps = (float) obj.optDouble("net_down_kb", 0.0);
            s.netUpKbps = (float) obj.optDouble("net_up_kb", 0.0);
            s.netIp = obj.optString("ip", "");
            s.netLink = obj.optString("net_link", obj.optString("mac", "--"));
            s.netMac = s.netLink;
            s.diskUsedGb = (float) obj.optDouble("disk_used_gb", 0.0);
            s.diskTotalGb = (float) obj.optDouble("disk_total_gb", 0.0);
            s.diskPct = clampPct(obj.optInt("disk_pct", 0));
            JSONArray arr = obj.optJSONArray("cpu_cores");
            if (arr != null) {
                s.cpuCorePcts = new int[arr.length()];
                for (int i = 0; i < arr.length(); i++) {
                    s.cpuCorePcts[i] = clampPct(arr.optInt(i, 0));
                }
            }
            s.gameName = obj.optString("game_name", "No Game");
            s.gameFps = obj.optInt("game_fps", 0);
            s.gpuMemGhz = (float) obj.optDouble("gpu_mem_ghz", 0.0);
            s.timestamp = System.currentTimeMillis();
            return s;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String formatSpeed(float kbps) {
        if (kbps <= 0.05f) return "0 KB/s";
        if (kbps >= 1024f * 1024f) {
            return String.format(Locale.US, "%.2f GB/s", kbps / (1024f * 1024f));
        }
        if (kbps >= 1024f) {
            return String.format(Locale.US, "%.1f MB/s", kbps / 1024f);
        }
        return String.format(Locale.US, "%.0f KB/s", kbps);
    }

    private static int parseInt(String str, int def) {
        try {
            return Integer.parseInt(str.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static long parseLong(String str, long def) {
        try {
            return Long.parseLong(str.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static float parseFloat(String str, float def) {
        try {
            return Float.parseFloat(str.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static int clampPct(int v) {
        if (v < 0) return 0;
        if (v > 100) return 100;
        return v;
    }

    public int getCpuPct() { return cpuPct; }
    public int getCpuTemp() { return cpuTemp; }
    public String getCpuModel() { return cpuModel; }
    public String getCpuBaseModel() { return (cpuBaseModel != null && !cpuBaseModel.isEmpty()) ? cpuBaseModel : cpuModel; }
    public long getCpuKhz() { return cpuKhz; }
    public int[] getCpuCorePcts() { return cpuCorePcts; }
    public int getCpuCoreCount() { return cpuCorePcts != null ? cpuCorePcts.length : 0; }
    public float getRamUsedGb() { return ramUsedGb; }
    public float getRamTotalGb() { return ramTotalGb; }
    public int getRamPct() { return ramPct; }
    public int getGpuPct() { return gpuPct; }
    public int getGpuTemp() { return gpuTemp; }
    public float getVramUsedGb() { return vramUsedGb; }
    public float getVramTotalGb() { return vramTotalGb; }
    public int getVramPct() { return vramPct; }
    public float getGpuMemGhz() { return gpuMemGhz; }
    public long getGpuMemKhz() { return gpuMemKhz; }
    public long getGpuCoreKhz() { return gpuCoreKhz; }
    public String getGpuName() { return gpuName; }
    public String getGpuBaseName() { return (gpuBaseName != null && !gpuBaseName.isEmpty()) ? gpuBaseName : gpuName; }
    public float getNetDownKbps() { return netDownKbps; }
    public float getNetUpKbps() { return netUpKbps; }
    public String getNetIp() { return netIp; }
    public String getNetMac() { return netMac; }
    public String getNetLink() { return (netLink != null && !netLink.isEmpty()) ? netLink : "--"; }
    public float getDiskUsedGb() { return diskUsedGb; }
    public float getDiskTotalGb() { return diskTotalGb; }
    public int getDiskPct() { return diskPct; }
    public String getGameName() { return (gameName != null && !gameName.isEmpty()) ? gameName : "No Game"; }
    public int getGameFps() { return gameFps; }
    public boolean isGameRunning() { return gameFps > 0 || (gameName != null && !gameName.isEmpty() && !gameName.equalsIgnoreCase("No Game")); }
    public long getTimestamp() { return timestamp; }

    /**
     * Smooths metrics across a 3-packet buffer to filter out single-frame jitter
     * while preserving the latest metadata (names, IPs, temps).
     */
    public static PcStats smoothFromBuffer(PcStats[] buffer, int count) {
        if (buffer == null || count <= 0) return null;
        if (count == 1) return buffer[0];

        PcStats latest = buffer[count - 1];
        if (latest == null) return null;

        PcStats s = new PcStats();
        s.cpuModel = latest.cpuModel;
        s.cpuBaseModel = latest.cpuBaseModel;
        s.gpuName = latest.gpuName;
        s.gpuBaseName = latest.gpuBaseName;
        s.cpuTemp = latest.cpuTemp;
        s.gpuTemp = latest.gpuTemp;
        s.netIp = latest.netIp;
        s.netMac = latest.netMac;
        s.netLink = latest.netLink;
        s.gameName = latest.gameName;
        s.diskUsedGb = latest.diskUsedGb;
        s.diskTotalGb = latest.diskTotalGb;
        s.diskPct = latest.diskPct;
        s.ramUsedGb = latest.ramUsedGb;
        s.ramTotalGb = latest.ramTotalGb;
        s.ramPct = latest.ramPct;
        s.vramTotalGb = latest.vramTotalGb;
        s.timestamp = System.currentTimeMillis();

        float totalCpuPct = 0;
        float totalGpuPct = 0;
        float totalVramPct = 0;
        float totalVramUsed = 0;
        long totalCpuKhz = 0;
        long totalGpuCoreKhz = 0;
        long totalGpuMemKhz = 0;
        float totalNetDown = 0;
        float totalNetUp = 0;
        int fpsSum = 0;
        int fpsCount = 0;

        int coreCount = latest.getCpuCoreCount();
        float[] coreSums = (coreCount > 0) ? new float[coreCount] : null;

        int validCount = 0;
        for (int i = 0; i < count; i++) {
            PcStats p = buffer[i];
            if (p == null) continue;
            validCount++;
            totalCpuPct += p.cpuPct;
            totalGpuPct += p.gpuPct;
            totalVramPct += p.vramPct;
            totalVramUsed += p.vramUsedGb;
            totalCpuKhz += p.cpuKhz;
            totalGpuCoreKhz += p.gpuCoreKhz;
            totalGpuMemKhz += p.gpuMemKhz;
            totalNetDown += p.netDownKbps;
            totalNetUp += p.netUpKbps;
            if (p.gameFps > 0) {
                fpsSum += p.gameFps;
                fpsCount++;
            }
            if (coreSums != null && p.cpuCorePcts != null) {
                for (int c = 0; c < coreCount && c < p.cpuCorePcts.length; c++) {
                    coreSums[c] += p.cpuCorePcts[c];
                }
            }
        }

        if (validCount == 0) return latest;

        s.cpuPct = clampPct(Math.round(totalCpuPct / validCount));
        s.gpuPct = clampPct(Math.round(totalGpuPct / validCount));
        s.vramPct = clampPct(Math.round(totalVramPct / validCount));
        s.vramUsedGb = totalVramUsed / validCount;
        s.cpuKhz = totalCpuKhz / validCount;
        s.gpuCoreKhz = totalGpuCoreKhz / validCount;
        s.gpuMemKhz = totalGpuMemKhz / validCount;
        s.gpuMemGhz = s.gpuMemKhz / 1_000_000f;
        s.netDownKbps = totalNetDown / validCount;
        s.netUpKbps = totalNetUp / validCount;
        s.gameFps = (fpsCount > 0) ? Math.round((float) fpsSum / fpsCount) : latest.gameFps;

        if (coreSums != null) {
            s.cpuCorePcts = new int[coreCount];
            for (int c = 0; c < coreCount; c++) {
                s.cpuCorePcts[c] = clampPct(Math.round(coreSums[c] / validCount));
            }
        }

        return s;
    }
}
