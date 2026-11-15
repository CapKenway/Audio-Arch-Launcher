package com.audioarch.launcher.utils;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.audioarch.launcher.models.PcStats;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class PcStatsServer {
    private static final String TAG = "PcStatsServer";
    public static final int DEFAULT_PORT = 12347;

    public interface Listener {
        void onStatsReceived(PcStats stats);
        void onConnectionChanged(boolean connected);
    }

    private static PcStatsServer instance;

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor();

    private final PcStats[] packetBuffer = new PcStats[3];
    private int bufferCount = 0;
    private int bufferHead = 0;
    private final AtomicBoolean isDispatchPending = new AtomicBoolean(false);

    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean running = false;
    private volatile boolean isConnected = false;
    private volatile PcStats lastStats = null;
    private int currentPort = DEFAULT_PORT;
    private volatile float currentIntervalSec = -0.05f;
    private volatile float measuredRateHz = 20.0f;
    private long lastPacketTime = 0;
    private volatile OutputStream activeOut = null;

    public static synchronized PcStatsServer getInstance() {
        if (instance == null) {
            instance = new PcStatsServer();
        }
        return instance;
    }

    private PcStatsServer() {
    }

    public synchronized void start(int port) {
        if (running && this.currentPort == port) {
            return;
        }
        stop();
        this.currentPort = port;
        running = true;
        serverThread = new Thread(this::listenLoop, "PcStatsServerThread");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Throwable ignored) {}
            serverSocket = null;
        }
        if (serverThread != null) {
            serverThread.interrupt();
            serverThread = null;
        }
        setConnected(false);
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
            if (lastStats != null) {
                l.onStatsReceived(lastStats);
            }
            l.onConnectionChanged(isConnected);
        }
    }

    public void removeListener(Listener l) {
        if (l != null) {
            listeners.remove(l);
        }
    }

    public boolean isConnected() {
        return isConnected;
    }

    public PcStats getLastStats() {
        return lastStats;
    }

    public void setIntervalSec(float intervalSec) {
        if (intervalSec < 0) {
            this.currentIntervalSec = intervalSec;
            this.measuredRateHz = 1.0f / Math.abs(intervalSec);
        } else {
            this.currentIntervalSec = Math.max(0.0f, Math.min(10.0f, intervalSec));
            if (this.currentIntervalSec > 0.01f) {
                this.measuredRateHz = 1.0f / this.currentIntervalSec;
            } else {
                this.measuredRateHz = 40.0f;
            }
        }
        sendRateToClient(this.currentIntervalSec);
    }

    public boolean isCallMode() {
        return currentIntervalSec < 0;
    }

    public long getCallIntervalMs() {
        if (currentIntervalSec < 0) {
            long ms = Math.round(Math.abs(currentIntervalSec) * 1000f);
            return Math.max(20, ms);
        }
        return 50;
    }

    public float getIntervalSec() {
        return currentIntervalSec;
    }

    public float getMeasuredRateHz() {
        return measuredRateHz;
    }

    public void requestPacket() {
        final OutputStream out = activeOut;
        if (out != null) {
            commandExecutor.execute(() -> {
                try {
                    out.write("REQ\n".getBytes("UTF-8"));
                    out.flush();
                } catch (Throwable ignored) {}
            });
        }
    }

    private synchronized void sendRateToClient(float intervalSec) {
        final OutputStream out = activeOut;
        if (out != null) {
            commandExecutor.execute(() -> {
                try {
                    String msg;
                    if (intervalSec < 0) {
                        long ms = Math.round(Math.abs(intervalSec) * 1000f);
                        msg = "MODE CALL " + ms + "\n";
                    } else if (intervalSec <= 0.01f) {
                        msg = "MODE STREAM\nRATE 0.0\n";
                    } else {
                        msg = "MODE STREAM\nRATE " + String.format(java.util.Locale.US, "%.2f", intervalSec) + "\n";
                    }
                    out.write(msg.getBytes("UTF-8"));
                    out.flush();
                    Log.i(TAG, "Sent control command to PC: " + msg.replace("\n", " ").trim());
                } catch (Throwable t) {
                    Log.w(TAG, "Failed sending command to PC: " + t.getMessage());
                }
            });
        }
    }

    private void listenLoop() {
        while (running) {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new java.net.InetSocketAddress(currentPort));
                Log.i(TAG, "Stats server listening on port " + currentPort);

                while (running) {
                    Socket client = null;
                    try {
                        client = serverSocket.accept();
                        client.setTcpNoDelay(true);
                        int soTimeout = Math.max(6000, (int) (Math.abs(currentIntervalSec) * 3500));
                        client.setSoTimeout(soTimeout);
                        activeOut = client.getOutputStream();
                        setConnected(true);
                        sendRateToClient(currentIntervalSec);

                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(client.getInputStream()), 256);
                        String line;
                        synchronized (packetBuffer) {
                            bufferCount = 0;
                            bufferHead = 0;
                        }
                        isDispatchPending.set(false);
                        lastPacketTime = 0;

                        while (running && (line = reader.readLine()) != null) {
                            long now = System.currentTimeMillis();
                            if (lastPacketTime > 0) {
                                long delta = now - lastPacketTime;
                                if (delta > 2) {
                                    float instantHz = 1000.0f / delta;
                                    measuredRateHz = (measuredRateHz * 0.8f) + (instantHz * 0.2f);
                                }
                            }
                            lastPacketTime = now;

                            line = line.trim();
                            if (line.isEmpty()) continue;

                            PcStats stats = line.startsWith("{")
                                    ? PcStats.parseJson(line)
                                    : PcStats.parseDelimited(line);

                            if (stats != null) {
                                synchronized (packetBuffer) {
                                    packetBuffer[bufferHead] = stats;
                                    bufferHead = (bufferHead + 1) % 3;
                                    if (bufferCount < 3) bufferCount++;
                                }

                                // 3-packet buffer smoothing with non-blocking UI queue dispatch
                                if (isDispatchPending.compareAndSet(false, true)) {
                                    mainHandler.post(() -> {
                                        isDispatchPending.set(false);
                                        PcStats[] snapshot = new PcStats[3];
                                        int count;
                                        synchronized (packetBuffer) {
                                            count = bufferCount;
                                            int start = (bufferHead - count + 3) % 3;
                                            for (int i = 0; i < count; i++) {
                                                snapshot[i] = packetBuffer[(start + i) % 3];
                                            }
                                        }
                                        PcStats smoothed = PcStats.smoothFromBuffer(snapshot, count);
                                        if (smoothed != null) {
                                            lastStats = smoothed;
                                            for (Listener l : listeners) {
                                                l.onStatsReceived(smoothed);
                                            }
                                        }
                                    });
                                }
                            }
                        }
                    } catch (SocketTimeoutException te) {
                        Log.d(TAG, "Client socket timeout");
                    } catch (Throwable t) {
                        if (running) {
                            Log.d(TAG, "Client session error: " + t.getMessage());
                        }
                    } finally {
                        activeOut = null;
                        setConnected(false);
                        synchronized (packetBuffer) {
                            bufferCount = 0;
                            bufferHead = 0;
                        }
                        isDispatchPending.set(false);
                        if (client != null) {
                            try { client.close(); } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable t) {
                if (running) {
                    Log.w(TAG, "ServerSocket error: " + t.getMessage());
                    setConnected(false);
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            } finally {
                if (serverSocket != null) {
                    try { serverSocket.close(); } catch (Throwable ignored) {}
                    serverSocket = null;
                }
            }
        }
        setConnected(false);
    }

    private void setConnected(boolean connected) {
        if (this.isConnected != connected) {
            this.isConnected = connected;
            mainHandler.post(() -> {
                for (Listener l : listeners) {
                    l.onConnectionChanged(connected);
                }
            });
        }
    }
}

