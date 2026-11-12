package com.audioarch.launcher.models;

public class DaemonStatus {
    private final String name;
    private final boolean running;
    private final String backendType;
    private final String logOutput;
    private final int pid;

    public DaemonStatus(String name, boolean running, String backendType, String logOutput, int pid) {
        this.name = name;
        this.running = running;
        this.backendType = backendType;
        this.logOutput = logOutput;
        this.pid = pid;
    }

    public String getName() { return name; }
    public boolean isRunning() { return running; }
    public String getBackendType() { return backendType; }
    public String getLogOutput() { return logOutput; }
    public int getPid() { return pid; }

    public static DaemonStatus stopped(String name, String backendType) {
        return new DaemonStatus(name, false, backendType, "", -1);
    }

    public static DaemonStatus fromPsLine(String name, String backendType, String psLine) {
        int pid = -1;
        try {
            String[] parts = psLine.trim().split("\\s+");
            if (parts.length > 1) {
                pid = Integer.parseInt(parts[1]);
            }
        } catch (NumberFormatException e) {
            // ignore
        }
        return new DaemonStatus(name, true, backendType, "", pid);
    }
}
