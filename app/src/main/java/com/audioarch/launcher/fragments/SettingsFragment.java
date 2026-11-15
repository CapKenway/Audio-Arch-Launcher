package com.audioarch.launcher.fragments;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.audioarch.launcher.R;
import com.audioarch.launcher.utils.DaemonController;

import java.io.FileWriter;

public class SettingsFragment extends Fragment {

    private SharedPreferences prefs;
    private TextView portValue, sampleRateValue, latencyValue, backendValue, orientationValue, statsRateValue;
    private Switch switchAutostart;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Context ctx = getContext();
        if (ctx == null) return;

        prefs = ctx.getSharedPreferences("audioarch_prefs", Context.MODE_PRIVATE);

        portValue = view.findViewById(R.id.setting_port_value);
        sampleRateValue = view.findViewById(R.id.setting_sample_rate_value);
        latencyValue = view.findViewById(R.id.setting_latency_value);
        backendValue = view.findViewById(R.id.setting_backend_value);
        orientationValue = view.findViewById(R.id.setting_orientation_value);
        statsRateValue = view.findViewById(R.id.setting_stats_rate_value);
        switchAutostart = view.findViewById(R.id.switch_autostart);

        refreshValues();

        // Port setting
        view.findViewById(R.id.setting_port).setOnClickListener(v -> {
            if (prefs != null) {
                showEditDialog("TCP Port", "port",
                        String.valueOf(prefs.getInt("port", DaemonController.DEFAULT_PORT)));
            }
        });

        // Sample rate setting
        view.findViewById(R.id.setting_sample_rate).setOnClickListener(v -> {
            Context c = getContext();
            if (c == null || prefs == null) return;

            String[] options = {"44100 Hz", "48000 Hz", "22050 Hz"};
            int[] values = {44100, 48000, 22050};
            int current = prefs.getInt("sample_rate", DaemonController.DEFAULT_SAMPLE_RATE);
            int selected = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i] == current) { selected = i; break; }
            }

            new AlertDialog.Builder(c)
                    .setTitle(R.string.settings_sample_rate)
                    .setSingleChoiceItems(options, selected, (dialog, which) -> {
                        prefs.edit().putInt("sample_rate", values[which]).apply();
                        refreshValues();
                        dialog.dismiss();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        // Audio Latency setting
        view.findViewById(R.id.setting_latency).setOnClickListener(v -> {
            Context c = getContext();
            if (c == null || prefs == null) return;

            String[] options = {
                    "10 ms (Ultra-low / Gaming)",
                    "15 ms (Default / Balanced)",
                    "20 ms (Smooth)",
                    "25 ms (Stable / Slower USB)",
                    "50 ms (Safe / Max buffer)",
                    "Custom..."
            };
            int[] values = {10, 15, 20, 25, 50, -1};
            int current = prefs.getInt("latency_ms", 15);
            int selected = 1;
            for (int i = 0; i < values.length - 1; i++) {
                if (values[i] == current) { selected = i; break; }
            }
            if (selected == 1 && current != 15) {
                selected = 5; // Custom
            }

            new AlertDialog.Builder(c)
                    .setTitle(R.string.settings_latency)
                    .setSingleChoiceItems(options, selected, (dialog, which) -> {
                        dialog.dismiss();
                        if (values[which] == -1) {
                            showEditDialog("Audio Latency (ms)", "latency_ms", String.valueOf(prefs.getInt("latency_ms", 15)));
                        } else {
                            saveLatency(values[which]);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        // Backend setting
        view.findViewById(R.id.setting_backend).setOnClickListener(v -> {
            Context c = getContext();
            if (c == null || prefs == null) return;

            String[] options = {
                    c.getString(R.string.settings_backend_auto),
                    c.getString(R.string.settings_backend_alsa),
                    c.getString(R.string.settings_backend_bt)
            };
            String[] keys = {"auto", "alsa", "bt"};
            String current = prefs.getString("backend", "auto");
            int selected = 0;
            for (int i = 0; i < keys.length; i++) {
                if (keys[i].equals(current)) { selected = i; break; }
            }

            new AlertDialog.Builder(c)
                    .setTitle(R.string.settings_backend)
                    .setSingleChoiceItems(options, selected, (dialog, which) -> {
                        prefs.edit().putString("backend", keys[which]).apply();
                        refreshValues();
                        dialog.dismiss();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        // Screen Orientation setting
        view.findViewById(R.id.setting_orientation).setOnClickListener(v -> {
            Context c = getContext();
            if (c == null || prefs == null) return;

            String[] options = {
                    c.getString(R.string.orientation_portrait),
                    c.getString(R.string.orientation_landscape),
                    c.getString(R.string.orientation_reverse_landscape),
                    c.getString(R.string.orientation_auto)
            };
            String[] keys = {"portrait", "landscape", "reverse_landscape", "auto"};
            String current = prefs.getString("orientation", "portrait");
            int selected = 0;
            for (int i = 0; i < keys.length; i++) {
                if (keys[i].equals(current)) { selected = i; break; }
            }

            new AlertDialog.Builder(c)
                    .setTitle(R.string.settings_orientation)
                    .setSingleChoiceItems(options, selected, (dialog, which) -> {
                        String chosen = keys[which];
                        prefs.edit().putString("orientation", chosen).apply();
                        if (getActivity() instanceof com.audioarch.launcher.LauncherActivity) {
                            ((com.audioarch.launcher.LauncherActivity) getActivity()).applyOrientation(chosen);
                        }
                        refreshValues();
                        dialog.dismiss();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        // Auto-start toggle
        if (switchAutostart != null && prefs != null) {
            switchAutostart.setChecked(prefs.getBoolean("autostart", false));
            switchAutostart.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.edit().putBoolean("autostart", isChecked).apply();
            });
        }

        // PC Stats Telemetry Refresh Rate setting
        View settingStatsRate = view.findViewById(R.id.setting_stats_rate);
        if (settingStatsRate != null) {
            settingStatsRate.setOnClickListener(v -> {
                Context c = getContext();
                if (c == null || prefs == null) return;

                final String[] options = {
                        "Call: 50 ms (20 Hz - Ultra Smooth)",
                        "Call: 80 ms (12.5 Hz - Balanced)",
                        "Call: 100 ms (10 Hz - Rock Solid)",
                        "Call: 250 ms (4 Hz - Fast)",
                        "Call: 500 ms (2 Hz - Balanced)",
                        "Call: 1.0s (1 Hz - Low CPU)",
                        "Call: Custom (ms)...",
                        "Stream: 0 ms (Uncapped - Fast as Possible)",
                        "Stream: 50 ms (20 Hz - Ultra Smooth)",
                        "Stream: 100 ms (10 Hz - Smooth Gaming)",
                        "Stream: 0.25s (4 Hz - Fast)",
                        "Stream: 0.5s (2 Hz - Balanced)",
                        "Stream: 1.0s (1 Hz - Standard / Default)",
                        "Stream: 2.0s (0.5 Hz - Power Saver)"
                };
                final float[] values = {
                        -0.05f,
                        -0.08f,
                        -0.10f,
                        -0.25f,
                        -0.50f,
                        -1.00f,
                        -999.0f,
                        0.0f,
                        0.05f,
                        0.10f,
                        0.25f,
                        0.50f,
                        1.00f,
                        2.00f
                };
                float current = prefs.getFloat("stats_refresh_interval", -0.05f);
                if (current == -1.0f) {
                    current = -0.05f;
                    prefs.edit().putFloat("stats_refresh_interval", -0.05f).apply();
                }
                int selected = 0;
                for (int i = 0; i < values.length; i++) {
                    if (values[i] == -999.0f) continue;
                    if (Math.abs(values[i] - current) < 0.02f) {
                        selected = i;
                        break;
                    }
                }
                if (current < 0 && selected == 0 && Math.abs(values[0] - current) >= 0.02f) {
                    selected = 6;
                }

                new AlertDialog.Builder(c)
                        .setTitle("Telemetry Update Frequency")
                        .setSingleChoiceItems(options, selected, (dialog, which) -> {
                            dialog.dismiss();
                            float chosen = values[which];
                            if (chosen == -999.0f) {
                                showCustomCallDialog(c);
                                return;
                            }
                            prefs.edit().putFloat("stats_refresh_interval", chosen).apply();
                            com.audioarch.launcher.utils.PcStatsServer.getInstance().setIntervalSec(chosen);
                            refreshValues();
                            Toast.makeText(c, "Stats rate set to " + options[which], Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }

        // About Section version setup
        TextView settingAboutVersion = view.findViewById(R.id.setting_about_version);
        if (settingAboutVersion != null) {
            settingAboutVersion.setText("v" + com.audioarch.launcher.BuildConfig.VERSION_NAME);
        }

        view.findViewById(R.id.setting_about).setOnClickListener(v -> {
            Context c = getContext();
            if (c != null) {
                Toast.makeText(c,
                        "Audio Arch Launcher v" + com.audioarch.launcher.BuildConfig.VERSION_NAME +
                                "\nUltra-low latency USB audio for Android",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void saveLatency(int latencyMs) {
        if (prefs != null) {
            prefs.edit().putInt("latency_ms", latencyMs).apply();
            // Also write to /data/local/tmp/audioarch_latency so audio_arch.sh can read it
            try {
                FileWriter fw = new FileWriter("/data/local/tmp/audioarch_latency");
                fw.write(String.valueOf(latencyMs));
                fw.close();
            } catch (Throwable ignored) {}
            // Also ensure it is written and world-readable via shell/root
            try {
                DaemonController dc = new DaemonController();
                dc.runCommand("echo " + latencyMs + " > /data/local/tmp/audioarch_latency && chmod 666 /data/local/tmp/audioarch_latency");
            } catch (Throwable ignored) {}
        }
        refreshValues();
    }

    private void refreshValues() {
        if (!isAdded() || getContext() == null || prefs == null) return;

        if (portValue != null) {
            portValue.setText(String.valueOf(prefs.getInt("port", DaemonController.DEFAULT_PORT)));
        }
        if (sampleRateValue != null) {
            sampleRateValue.setText(prefs.getInt("sample_rate", DaemonController.DEFAULT_SAMPLE_RATE) + " Hz");
        }
        if (latencyValue != null) {
            int lat = prefs.getInt("latency_ms", 15);
            if (lat == 15) {
                latencyValue.setText("15 ms (Default)");
            } else {
                latencyValue.setText(lat + " ms");
            }
        }

        if (backendValue != null) {
            String backend = prefs.getString("backend", "auto");
            switch (backend) {
                case "alsa":
                    backendValue.setText(R.string.settings_backend_alsa);
                    break;
                case "bt":
                    backendValue.setText(R.string.settings_backend_bt);
                    break;
                default:
                    backendValue.setText(R.string.settings_backend_auto);
                    break;
            }
        }

        if (orientationValue != null) {
            String orient = prefs.getString("orientation", "portrait");
            switch (orient) {
                case "landscape":
                    orientationValue.setText(R.string.orientation_landscape);
                    break;
                case "reverse_landscape":
                    orientationValue.setText(R.string.orientation_reverse_landscape);
                    break;
                case "auto":
                    orientationValue.setText(R.string.orientation_auto);
                    break;
                default:
                    orientationValue.setText(R.string.orientation_portrait);
                    break;
            }
        }

        if (statsRateValue != null) {
            float interval = prefs.getFloat("stats_refresh_interval", -0.05f);
            if (interval == -1.0f) {
                interval = -0.05f;
                prefs.edit().putFloat("stats_refresh_interval", -0.05f).apply();
            }
            if (interval < 0) {
                long ms = Math.round(Math.abs(interval) * 1000f);
                if (ms == 50) {
                    statsRateValue.setText("Call: 50 ms (20 Hz - Ultra Smooth)");
                } else if (ms == 80) {
                    statsRateValue.setText("Call: 80 ms (12.5 Hz - Balanced)");
                } else if (ms == 100) {
                    statsRateValue.setText("Call: 100 ms (10 Hz - Rock Solid)");
                } else if (ms == 250) {
                    statsRateValue.setText("Call: 250 ms (4 Hz - Fast)");
                } else if (ms == 500) {
                    statsRateValue.setText("Call: 500 ms (2 Hz - Balanced)");
                } else if (ms == 1000) {
                    statsRateValue.setText("Call: 1.0s (1 Hz - Low CPU)");
                } else {
                    statsRateValue.setText("Call: " + ms + " ms (Custom)");
                }
            } else if (interval <= 0.01f) {
                statsRateValue.setText("Stream: 0 ms (Uncapped - Fast as Possible)");
            } else if (Math.abs(interval - 0.05f) < 0.02f) {
                statsRateValue.setText("Stream: 50 ms (20 Hz - Ultra Smooth)");
            } else if (Math.abs(interval - 0.10f) < 0.03f) {
                statsRateValue.setText("Stream: 100 ms (10 Hz - Smooth Gaming)");
            } else if (Math.abs(interval - 0.25f) < 0.05f) {
                statsRateValue.setText("Stream: 0.25s (4 Hz - Fast)");
            } else if (Math.abs(interval - 0.5f) < 0.05f) {
                statsRateValue.setText("Stream: 0.5s (2 Hz - Balanced)");
            } else if (Math.abs(interval - 1.0f) < 0.05f) {
                statsRateValue.setText("Stream: 1.0s (1 Hz - Standard / Default)");
            } else if (Math.abs(interval - 2.0f) < 0.05f) {
                statsRateValue.setText("Stream: 2.0s (0.5 Hz - Power Saver)");
            } else {
                statsRateValue.setText(String.format(java.util.Locale.US, "Stream: %.2fs (%.1f Hz)", interval, (1.0f / interval)));
            }
        }
    }

    private void showCustomCallDialog(Context c) {
        if (prefs == null) return;
        final EditText input = new EditText(c);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        long currentMs = Math.round(Math.abs(prefs.getFloat("stats_refresh_interval", -0.05f)) * 1000f);
        if (currentMs < 20 || currentMs > 10000) currentMs = 50;
        input.setText(String.valueOf(currentMs));
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(c)
                .setTitle("Custom Call Interval (ms)")
                .setMessage("Enter polling interval in milliseconds (20 - 5000 ms).\nPhone will request packets at this exact cadence.")
                .setView(input)
                .setPositiveButton("Set", (dialog, which) -> {
                    String valStr = input.getText().toString().trim();
                    try {
                        int valMs = Integer.parseInt(valStr);
                        valMs = Math.max(20, Math.min(10000, valMs));
                        float chosen = -(valMs / 1000.0f);
                        prefs.edit().putFloat("stats_refresh_interval", chosen).apply();
                        com.audioarch.launcher.utils.PcStatsServer.getInstance().setIntervalSec(chosen);
                        refreshValues();
                        Toast.makeText(c, "Call Mode set to " + valMs + " ms (" + String.format(java.util.Locale.US, "%.1f", 1000f / valMs) + " Hz)", Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                        Toast.makeText(c, "Invalid number", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showEditDialog(String title, final String key, String currentValue) {
        Context c = getContext();
        if (c == null) return;

        final EditText input = new EditText(c);
        input.setText(currentValue);
        input.setSelection(currentValue.length());
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);

        new AlertDialog.Builder(c)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            int value = Integer.parseInt(input.getText().toString().trim());
                            if ("latency_ms".equals(key)) {
                                saveLatency(value);
                            } else {
                                if (prefs != null) {
                                    prefs.edit().putInt(key, value).apply();
                                }
                                refreshValues();
                            }
                        } catch (NumberFormatException e) {
                            Context ctx = getContext();
                            if (ctx != null) {
                                Toast.makeText(ctx, "Invalid number", Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
