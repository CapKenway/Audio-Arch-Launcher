package com.audioarch.launcher.utils;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class BluetoothHelper {

    public interface OnDeviceFoundListener {
        void onDeviceFound(BluetoothDevice device);
        void onDiscoveryFinished();
    }

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private final List<BluetoothDevice> availableDevices = new ArrayList<>();
    private BroadcastReceiver discoveryReceiver;
    private OnDeviceFoundListener listener;
    private BluetoothProfile a2dpProxy;

    public BluetoothHelper(Context context) {
        this.context = context.getApplicationContext();
        BluetoothAdapter adapter = null;
        try {
            adapter = BluetoothAdapter.getDefaultAdapter();
        } catch (Throwable ignored) {}
        this.bluetoothAdapter = adapter;

        if (bluetoothAdapter != null) {
            try {
                bluetoothAdapter.getProfileProxy(this.context, new BluetoothProfile.ServiceListener() {
                    @Override
                    public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        if (profile == BluetoothProfile.A2DP) {
                            a2dpProxy = proxy;
                        }
                    }
                    @Override
                    public void onServiceDisconnected(int profile) {
                        if (profile == BluetoothProfile.A2DP) {
                            a2dpProxy = null;
                        }
                    }
                }, BluetoothProfile.A2DP);
            } catch (Throwable ignored) {}
        }
    }

    public boolean isBluetoothAvailable() {
        return bluetoothAdapter != null;
    }

    public boolean isBluetoothEnabled() {
        try {
            return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    public List<BluetoothDevice> getPairedDevices() {
        List<BluetoothDevice> result = new ArrayList<>();
        if (bluetoothAdapter == null) return result;
        try {
            Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
            if (bonded != null) {
                result.addAll(bonded);
            }
        } catch (Throwable ignored) {}
        return result;
    }

    public String getConnectedDeviceName() {
        // Method 1: Check via A2DP profile proxy
        try {
            if (a2dpProxy != null) {
                List<BluetoothDevice> connected = a2dpProxy.getConnectedDevices();
                if (connected != null && !connected.isEmpty()) {
                    String name = connected.get(0).getName();
                    if (name != null && !name.trim().isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Throwable ignored) {}

        // Method 2: Check AudioManager
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && am.isBluetoothA2dpOn()) {
                List<BluetoothDevice> paired = getPairedDevices();
                if (!paired.isEmpty()) {
                    String name = paired.get(0).getName();
                    if (name != null && !name.isEmpty()) return name;
                }
                return "Bluetooth Audio";
            }
        } catch (Throwable ignored) {}

        return null;
    }

    public void startDiscovery(OnDeviceFoundListener listener) {
        this.listener = listener;
        availableDevices.clear();

        if (discoveryReceiver != null) {
            try { context.unregisterReceiver(discoveryReceiver); } catch (Throwable ignored) {}
            discoveryReceiver = null;
        }

        discoveryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null) return;
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    try {
                        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                        if (device != null && device.getName() != null) {
                            availableDevices.add(device);
                            if (BluetoothHelper.this.listener != null) {
                                BluetoothHelper.this.listener.onDeviceFound(device);
                            }
                        }
                    } catch (Throwable ignored) {}
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                    if (BluetoothHelper.this.listener != null) {
                        try {
                            BluetoothHelper.this.listener.onDiscoveryFinished();
                        } catch (Throwable ignored) {}
                    }
                }
            }
        };

        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_FOUND);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            context.registerReceiver(discoveryReceiver, filter);

            if (bluetoothAdapter != null) {
                bluetoothAdapter.cancelDiscovery();
                bluetoothAdapter.startDiscovery();
            }
        } catch (Throwable ignored) {}
    }

    public void stopDiscovery() {
        try {
            if (bluetoothAdapter != null) {
                bluetoothAdapter.cancelDiscovery();
            }
        } catch (Throwable ignored) {}
        if (discoveryReceiver != null) {
            try { context.unregisterReceiver(discoveryReceiver); } catch (Throwable ignored) {}
            discoveryReceiver = null;
        }
    }

    public void connectDevice(BluetoothDevice device) {
        if (device == null) return;
        try {
            if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                Method method = device.getClass().getMethod("createBond");
                method.invoke(device);
            }
        } catch (Throwable ignored) {}

        try {
            if (a2dpProxy != null) {
                Method connectMethod = a2dpProxy.getClass().getMethod("connect", BluetoothDevice.class);
                connectMethod.invoke(a2dpProxy, device);
            }
        } catch (Throwable ignored) {}
    }

    public List<BluetoothDevice> getAvailableDevices() {
        return new ArrayList<>(availableDevices);
    }

    public void cleanup() {
        stopDiscovery();
        try {
            if (a2dpProxy != null && bluetoothAdapter != null) {
                bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, a2dpProxy);
                a2dpProxy = null;
            }
        } catch (Throwable ignored) {}
    }
}
