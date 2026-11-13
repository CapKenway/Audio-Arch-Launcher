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
    private String gpuName;
    private float netDownKbps;
    private float netUpKbps;
    private String netIp;
    private String netMac;
    private float diskUsedGb;
    private float diskTotalGb;
    private int diskPct;
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
        this.gpuName = "PC GPU";
        this.netDownKbps = 0f;
        this.netUpKbps = 0f;
        this.netIp = "";
        this.netMac = "";
        this.diskUsedGb = 0f;
        this.diskTotalGb = 0f;
        this.diskPct = 0;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Fast parser for delimited format:
     * STAT|cpu_pct|cpu_temp|cpu_model|ram_used_gb|ram_total_gb|ram_pct|gpu_pct|gpu_temp|vram_used_gb|vram_total_gb|vram_pct|gpu_name|net_down_kb|net_up_kb|ip|mac|disk_used_gb|disk_total_gb|disk_pct|cores_str
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
            if (parts.length > 16) s.netMac = parts[16].trim().toUpperCase(Locale.US);
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
            s.netMac = obj.optString("mac", "").toUpperCase(Locale.US);
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
    public String getGpuName() { return gpuName; }
    public float getNetDownKbps() { return netDownKbps; }
    public float getNetUpKbps() { return netUpKbps; }
    public String getNetIp() { return netIp; }
    public String getNetMac() { return netMac; }
    public float getDiskUsedGb() { return diskUsedGb; }
    public float getDiskTotalGb() { return diskTotalGb; }
    public int getDiskPct() { return diskPct; }
    public long getTimestamp() { return timestamp; }
}
