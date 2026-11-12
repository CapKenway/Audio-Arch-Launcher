package com.audioarch.launcher.fragments;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.audioarch.launcher.R;
import com.audioarch.launcher.models.DaemonStatus;
import com.audioarch.launcher.utils.AudioController;
import com.audioarch.launcher.utils.BluetoothHelper;
import com.audioarch.launcher.utils.DaemonController;

import java.util.ArrayList;
import java.util.List;

public class DashboardFragment extends Fragment {

    private View statusC, statusBt;
    private Button btnToggleC, btnToggleBt;
    private SeekBar volumeSeekbar;
    private TextView volumeText, btDeviceName;
    private TextView pillCVersion, pillBtVersion;
    private Button btnBluetooth;

    private DaemonController daemonController;
    private AudioController audioController;
    private BluetoothHelper bluetoothHelper;
    private SharedPreferences prefs;

    private boolean isCRunning = false;
    private boolean isBtRunning = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable statusChecker;
    private boolean isChecking = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Find views
        statusC = view.findViewById(R.id.status_c);
        statusBt = view.findViewById(R.id.status_bt);
        btnToggleC = view.findViewById(R.id.btn_toggle_c);
        btnToggleBt = view.findViewById(R.id.btn_toggle_bt);
        volumeSeekbar = view.findViewById(R.id.volume_seekbar);
        volumeText = view.findViewById(R.id.volume_text);
        btDeviceName = view.findViewById(R.id.bt_device_name);
        btnBluetooth = view.findViewById(R.id.btn_bluetooth);
        pillCVersion = view.findViewById(R.id.pill_c_version);
        pillBtVersion = view.findViewById(R.id.pill_bt_version);

        View titleHeader = view.findViewById(R.id.title_header);
        if (titleHeader != null) {
            titleHeader.setOnClickListener(v -> {
                if (getActivity() instanceof com.audioarch.launcher.LauncherActivity) {
                    com.audioarch.launcher.LauncherActivity act = (com.audioarch.launcher.LauncherActivity) getActivity();
                    if (act.isBottomNavVisible()) {
                        act.hideBottomBar();
                    } else {
                        act.showBottomBar();
                    }
                }
            });
        }

        Context ctx = getContext();
        if (ctx == null) return;

        daemonController = new DaemonController();
        audioController = new AudioController(ctx);
        bluetoothHelper = new BluetoothHelper(ctx);
        prefs = ctx.getSharedPreferences("audioarch_prefs", Context.MODE_PRIVATE);

        // Setup volume seekbar
        try {
            int maxVol = audioController.getMaxVolume();
            volumeSeekbar.setMax(maxVol);
            volumeSeekbar.setProgress(audioController.getCurrentVolume());
            updateVolumeText();
        } catch (Throwable ignored) {}

        volumeSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && audioController != null) {
                    try {
                        audioController.setVolume(progress);
                        updateVolumeText();
                    } catch (Throwable ignored) {}
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Single Toggle Button for ALSA Daemon
        btnToggleC.setOnClickListener(v -> {
            if (isCRunning) {
                stopCDaemonAsync();
            } else {
                startCDaemonAsync();
            }
        });

        // Single Toggle Button for AudioTrack Daemon
        btnToggleBt.setOnClickListener(v -> {
            if (isBtRunning) {
                stopBtDaemonAsync();
            } else {
                startBtDaemonAsync();
            }
        });

        // Bluetooth dialog
        btnBluetooth.setOnClickListener(v -> showBluetoothDialog());

        // Periodic status check
        statusChecker = new Runnable() {
            @Override
            public void run() {
                if (isSafe()) {
                    refreshStatus();
                    handler.postDelayed(this, 2500);
                }
            }
        };
        handler.post(statusChecker);
    }

    private boolean isSafe() {
        return isAdded() && getContext() != null && getActivity() != null && getView() != null;
    }

    private void updateVolumeText() {
        if (!isSafe() || audioController == null || volumeText == null) return;
        try {
            int pct = audioController.getVolumePercentage();
            volumeText.setText(pct + "%");
        } catch (Throwable ignored) {}
    }

    private int getPort() {
        if (prefs == null) return DaemonController.DEFAULT_PORT;
        return prefs.getInt("port", DaemonController.DEFAULT_PORT);
    }

    private int getSampleRate() {
        if (prefs == null) return DaemonController.DEFAULT_SAMPLE_RATE;
        return prefs.getInt("sample_rate", DaemonController.DEFAULT_SAMPLE_RATE);
    }

    @SuppressWarnings("deprecation")
    private void startCDaemonAsync() {
        if (btnToggleC != null) btnToggleC.setText("Starting...");
        final Context ctx = getContext();
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... voids) {
                try {
                    return daemonController.startCDaemon(ctx, getPort(), getSampleRate());
                } catch (Throwable t) {
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean success) {
                if (!isSafe()) return;
                refreshStatus();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @SuppressWarnings("deprecation")
    private void stopCDaemonAsync() {
        if (btnToggleC != null) btnToggleC.setText("Stopping...");
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                try {
                    daemonController.stopCDaemon();
                } catch (Throwable ignored) {}
                return null;
            }

            @Override
            protected void onPostExecute(Void v) {
                if (!isSafe()) return;
                refreshStatus();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @SuppressWarnings("deprecation")
    private void startBtDaemonAsync() {
        if (btnToggleBt != null) btnToggleBt.setText("Starting...");
        final Context ctx = getContext();
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... voids) {
                try {
                    return daemonController.startBtDaemon(ctx, getPort(), getSampleRate());
                } catch (Throwable t) {
                    return false;
                }
            }

            @Override
            protected void onPostExecute(Boolean success) {
                if (!isSafe()) return;
                refreshStatus();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @SuppressWarnings("deprecation")
    private void stopBtDaemonAsync() {
        if (btnToggleBt != null) btnToggleBt.setText("Stopping...");
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                try {
                    daemonController.stopBtDaemon();
                } catch (Throwable ignored) {}
                return null;
            }

            @Override
            protected void onPostExecute(Void v) {
                if (!isSafe()) return;
                refreshStatus();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @SuppressWarnings("deprecation")
    private void refreshStatus() {
        if (isChecking) return;
        isChecking = true;

        new AsyncTask<Void, Void, Object[]>() {
            @Override
            protected Object[] doInBackground(Void... voids) {
                try {
                    int port = getPort();
                    DaemonStatus cStatus = daemonController.checkCDaemon(port);
                    DaemonStatus btStatus = daemonController.checkBtDaemon(port);
                    String btDevice = null;
                    if (bluetoothHelper != null) {
                        btDevice = bluetoothHelper.getConnectedDeviceName();
                    }
                    String daemonVer = daemonController != null ? daemonController.getDaemonVersion() : DaemonController.DEFAULT_DAEMON_VERSION;
                    return new Object[]{cStatus, btStatus, btDevice, daemonVer};
                } catch (Throwable t) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Object[] results) {
                isChecking = false;
                if (!isSafe() || results == null) return;

                try {
                    DaemonStatus cStatus = (DaemonStatus) results[0];
                    DaemonStatus btStatus = (DaemonStatus) results[1];
                    String btDevice = (String) results[2];
                    String daemonVer = (String) results[3];
                    if (daemonVer == null || daemonVer.isEmpty()) {
                        daemonVer = DaemonController.DEFAULT_DAEMON_VERSION;
                    }

                    isCRunning = (cStatus != null && cStatus.isRunning());
                    isBtRunning = (btStatus != null && btStatus.isRunning());

                    // Card-level Version Pills (Visible ONLY when running)
                    if (pillCVersion != null) {
                        if (isCRunning) {
                            pillCVersion.setText("v" + daemonVer);
                            pillCVersion.setVisibility(View.VISIBLE);
                        } else {
                            pillCVersion.setVisibility(View.GONE);
                        }
                    }
                    if (pillBtVersion != null) {
                        if (isBtRunning) {
                            pillBtVersion.setText("v" + daemonVer);
                            pillBtVersion.setVisibility(View.VISIBLE);
                        } else {
                            pillBtVersion.setVisibility(View.GONE);
                        }
                    }

                    // ALSA status indicator & single toggle button
                    if (statusC != null) {
                        statusC.setBackgroundResource(isCRunning
                                ? R.drawable.bg_status_running : R.drawable.bg_status_stopped);
                    }
                    if (btnToggleC != null) {
                        if (isCRunning) {
                            btnToggleC.setText(R.string.btn_stop);
                            btnToggleC.setBackgroundResource(R.drawable.bg_button_danger);
                        } else {
                            btnToggleC.setText(R.string.btn_start);
                            btnToggleC.setBackgroundResource(R.drawable.bg_button);
                        }
                    }

                    // AudioTrack status indicator & single toggle button
                    if (statusBt != null) {
                        statusBt.setBackgroundResource(isBtRunning
                                ? R.drawable.bg_status_running : R.drawable.bg_status_stopped);
                    }
                    if (btnToggleBt != null) {
                        if (isBtRunning) {
                            btnToggleBt.setText(R.string.btn_stop);
                            btnToggleBt.setBackgroundResource(R.drawable.bg_button_danger);
                        } else {
                            btnToggleBt.setText(R.string.btn_start);
                            btnToggleBt.setBackgroundResource(R.drawable.bg_button);
                        }
                    }

                    // Bluetooth device name
                    if (btDeviceName != null) {
                        if (btDevice != null && !btDevice.isEmpty()) {
                            Context c = getContext();
                            if (c != null) {
                                btDeviceName.setText(c.getString(R.string.bluetooth_connected, btDevice));
                            } else {
                                btDeviceName.setText(btDevice);
                            }
                        } else {
                            btDeviceName.setText(R.string.bluetooth_none);
                        }
                    }

                    // Keep volume slider in sync with hardware keys
                    if (volumeSeekbar != null && audioController != null) {
                        volumeSeekbar.setProgress(audioController.getCurrentVolume());
                        updateVolumeText();
                    }
                } catch (Throwable ignored) {}
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void showBluetoothDialog() {
        final Context ctx = getContext();
        if (ctx == null || bluetoothHelper == null) return;

        if (!bluetoothHelper.isBluetoothAvailable()) {
            Toast.makeText(ctx, "Bluetooth not available", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!bluetoothHelper.isBluetoothEnabled()) {
            Toast.makeText(ctx, "Please enable Bluetooth", Toast.LENGTH_SHORT).show();
            return;
        }

        View dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_bluetooth, null);
        ListView pairedList = dialogView.findViewById(R.id.paired_devices_list);
        ListView availableList = dialogView.findViewById(R.id.available_devices_list);
        final Button btnScan = dialogView.findViewById(R.id.btn_scan);

        // Paired devices
        final List<BluetoothDevice> paired = bluetoothHelper.getPairedDevices();
        List<String> pairedNames = new ArrayList<>();
        for (BluetoothDevice d : paired) {
            String name = d.getName();
            pairedNames.add(name != null ? name : d.getAddress());
        }
        ArrayAdapter<String> pairedAdapter = new ArrayAdapter<>(ctx,
                android.R.layout.simple_list_item_1, pairedNames);
        pairedList.setAdapter(pairedAdapter);

        // Available devices
        final List<BluetoothDevice> available = new ArrayList<>();
        final List<String> availableNames = new ArrayList<>();
        final ArrayAdapter<String> availableAdapter = new ArrayAdapter<>(ctx,
                android.R.layout.simple_list_item_1, availableNames);
        availableList.setAdapter(availableAdapter);

        final AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setView(dialogView)
                .create();

        pairedList.setOnItemClickListener((parent, v, position, id) -> {
            if (position < paired.size()) {
                bluetoothHelper.connectDevice(paired.get(position));
                Toast.makeText(ctx, "Connecting...", Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
        });

        availableList.setOnItemClickListener((parent, v, position, id) -> {
            if (position < available.size()) {
                bluetoothHelper.connectDevice(available.get(position));
                Toast.makeText(ctx, "Pairing...", Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
        });

        btnScan.setOnClickListener(v -> {
            availableNames.clear();
            available.clear();
            availableAdapter.notifyDataSetChanged();
            btnScan.setText("Scanning...");
            btnScan.setEnabled(false);

            bluetoothHelper.startDiscovery(new BluetoothHelper.OnDeviceFoundListener() {
                @Override
                public void onDeviceFound(final BluetoothDevice device) {
                    FragmentActivity act = getActivity();
                    if (act == null || !isSafe()) return;

                    act.runOnUiThread(() -> {
                        try {
                            if (!available.contains(device)) {
                                available.add(device);
                                String name = device.getName();
                                availableNames.add(name != null ? name : device.getAddress());
                                availableAdapter.notifyDataSetChanged();
                            }
                        } catch (Throwable ignored) {}
                    });
                }

                @Override
                public void onDiscoveryFinished() {
                    FragmentActivity act = getActivity();
                    if (act == null || !isSafe()) return;

                    act.runOnUiThread(() -> {
                        try {
                            btnScan.setText(R.string.bluetooth_scan);
                            btnScan.setEnabled(true);
                        } catch (Throwable ignored) {}
                    });
                }
            });
        });

        dialog.setOnDismissListener(d -> {
            if (bluetoothHelper != null) {
                bluetoothHelper.stopDiscovery();
            }
        });
        dialog.show();
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(statusChecker);
        if (bluetoothHelper != null) {
            bluetoothHelper.cleanup();
        }
        super.onDestroyView();
    }
}
