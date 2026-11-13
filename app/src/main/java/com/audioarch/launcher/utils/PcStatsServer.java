package com.audioarch.launcher.utils;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.audioarch.launcher.models.PcStats;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CopyOnWriteArrayList;

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

    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean running = false;
    private volatile boolean isConnected = false;
    private volatile PcStats lastStats = null;
    private int currentPort = DEFAULT_PORT;

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

    private void listenLoop() {
        while (running) {
            try {
                serverSocket = new ServerSocket(currentPort);
                serverSocket.setReuseAddress(true);
                Log.i(TAG, "Stats server listening on port " + currentPort);

                while (running) {
                    Socket client = null;
                    try {
                        client = serverSocket.accept();
                        client.setTcpNoDelay(true);
                        client.setSoTimeout(3500); // 3.5s timeout for heartbeat
                        setConnected(true);

                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(client.getInputStream()), 256);
                        String line;
                        while (running && (line = reader.readLine()) != null) {
                            line = line.trim();
                            if (line.isEmpty()) continue;

                            PcStats stats = line.startsWith("{")
                                    ? PcStats.parseJson(line)
                                    : PcStats.parseDelimited(line);

                            if (stats != null) {
                                lastStats = stats;
                                dispatchStats(stats);
                            }
                        }
                    } catch (SocketTimeoutException te) {
                        Log.d(TAG, "Client socket timeout");
                    } catch (Throwable t) {
                        if (running) {
                            Log.d(TAG, "Client session error: " + t.getMessage());
                        }
                    } finally {
                        setConnected(false);
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
                        Thread.sleep(2000);
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

    private void dispatchStats(PcStats stats) {
        mainHandler.post(() -> {
            for (Listener l : listeners) {
                l.onStatsReceived(stats);
            }
        });
    }
}

