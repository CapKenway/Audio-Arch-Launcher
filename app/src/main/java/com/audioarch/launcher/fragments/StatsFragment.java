package com.audioarch.launcher.fragments;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.audioarch.launcher.LauncherActivity;
import com.audioarch.launcher.R;
import com.audioarch.launcher.models.DaemonStatus;
import com.audioarch.launcher.models.PcStats;
import com.audioarch.launcher.utils.DaemonController;
import com.audioarch.launcher.utils.PcStatsServer;
import com.audioarch.launcher.views.FpsGraphView;

import java.lang.ref.WeakReference;
import java.util.Locale;

public class StatsFragment extends Fragment implements PcStatsServer.Listener {

    private View dotPcStatus, dotDaemonPill, pillDaemonStatus, headerBar, containerPcStatus;
    private TextView tvPcStatus, tvDaemonPill;
    private long lastPacketTime = 0;
    private float measuredRateHz = 1.0f;

    // Smooth transition & slow fallback fields
    private float currentCpuPct = 0f;
    private float targetCpuPct = 0f;
    private float currentGpuPct = 0f;
    private float targetGpuPct = 0f;
    private float[] currentCorePcts = null;
    private int[] targetCorePcts = null;

    private float smoothCpuKhz = 0f;
    private float targetCpuKhz = 0f;
    private float smoothGpuCoreKhz = 0f;
    private float targetGpuCoreKhz = 0f;
    private float smoothGpuMemKhz = 0f;
    private float targetGpuMemKhz = 0f;

    private String baseCpuName = "CPU";
    private String baseGpuName = "GPU";
    private float lastVramUsed = 0f;
    private float lastVramTotal = 0f;
    private boolean isSmoothLoopActive = false;

    // CPU Card
    private TextView tvCpuDetail, tvCpuPct, tvCpuTemp;
    private ProgressBar pbCpu;
    private LinearLayout containerCpuCores;
    private ProgressBar[] pbCores = null;
    private int[] cachedCoreProgress = null;

    // RAM & DISK Card
    private TextView tvRamDetail, tvRamPct;
    private ProgressBar pbRam;
    private TextView tvDiskDetail, tvDiskPct;
    private ProgressBar pbDisk;

    // GPU Card (Core & VRAM)
    private TextView tvGpuDetail, tvGpuPct, tvGpuTemp;
    private ProgressBar pbGpu;
    private TextView tvVramDetail, tvVramPct;
    private ProgressBar pbVram;

    // Network Card
    private TextView tvNetSpeedSummary, tvNetIp, tvNetLink;

    // Game & FPS Dynamic Slot
    private View dividerNetGame, containerGame;
    private TextView tvGameName, tvGameFps;
    private FpsGraphView viewFpsGraph;

    private DaemonController daemonController;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable daemonPollRunnable;
    private boolean isVisibleToUser = false;

    // String & value caches to prevent unnecessary view invalidations and GC allocations
    private String cachedCpuPct = "";
    private String cachedCpuTemp = "";
    private String cachedCpuDetail = "";
    private String cachedRamPct = "";
    private String cachedRamDetail = "";
    private String cachedDiskPct = "";
    private String cachedDiskDetail = "";
    private String cachedGpuPct = "";
    private String cachedGpuTemp = "";
    private String cachedGpuDetail = "";
    private String cachedVramPct = "";
    private String cachedVramDetail = "";
    private String cachedNetSummary = "";
    private String cachedNetIp = "";
    private String cachedNetLink = "";
    private String cachedGameName = "";
    private String cachedGameFps = "";
    private int cachedCpuProgress = -1;
    private int cachedRamProgress = -1;
    private int cachedDiskProgress = -1;
    private int cachedGpuProgress = -1;
    private int cachedVramProgress = -1;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_stats, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        dotPcStatus = view.findViewById(R.id.dot_pc_status);
        tvPcStatus = view.findViewById(R.id.tv_pc_status);
        containerPcStatus = view.findViewById(R.id.container_pc_status);
        if (containerPcStatus != null) {
            containerPcStatus.setOnClickListener(v -> showRateSelectionDialog());
        }
        dotDaemonPill = view.findViewById(R.id.dot_daemon_pill);
        tvDaemonPill = view.findViewById(R.id.tv_daemon_pill);
        pillDaemonStatus = view.findViewById(R.id.pill_daemon_status);

        // CPU views
        tvCpuDetail = view.findViewById(R.id.tv_cpu_detail);
        tvCpuPct = view.findViewById(R.id.tv_cpu_pct);
        tvCpuTemp = view.findViewById(R.id.tv_cpu_temp);
        pbCpu = view.findViewById(R.id.pb_cpu);
        containerCpuCores = view.findViewById(R.id.container_cpu_cores);

        // RAM & DISK views
        tvRamDetail = view.findViewById(R.id.tv_ram_detail);
        tvRamPct = view.findViewById(R.id.tv_ram_pct);
        pbRam = view.findViewById(R.id.pb_ram);
        tvDiskDetail = view.findViewById(R.id.tv_disk_detail);
        tvDiskPct = view.findViewById(R.id.tv_disk_pct);
        pbDisk = view.findViewById(R.id.pb_disk);

        // GPU views
        tvGpuDetail = view.findViewById(R.id.tv_gpu_detail);
        tvGpuPct = view.findViewById(R.id.tv_gpu_pct);
        tvGpuTemp = view.findViewById(R.id.tv_gpu_temp);
        pbGpu = view.findViewById(R.id.pb_gpu);
        tvVramDetail = view.findViewById(R.id.tv_vram_detail);
        tvVramPct = view.findViewById(R.id.tv_vram_pct);
        pbVram = view.findViewById(R.id.pb_vram);

        // Network views
        tvNetSpeedSummary = view.findViewById(R.id.tv_net_speed_summary);
        tvNetIp = view.findViewById(R.id.tv_net_ip);
        tvNetLink = view.findViewById(R.id.tv_net_link);

        // Game dynamic views
        dividerNetGame = view.findViewById(R.id.divider_net_game);
        containerGame = view.findViewById(R.id.container_game);
        tvGameName = view.findViewById(R.id.tv_game_name);
        tvGameFps = view.findViewById(R.id.tv_game_fps);
        viewFpsGraph = view.findViewById(R.id.view_fps_graph);

        headerBar = view.findViewById(R.id.header_bar);
        if (headerBar != null) {
            headerBar.setOnClickListener(v -> {
                if (getActivity() instanceof LauncherActivity) {
                    LauncherActivity act = (LauncherActivity) getActivity();
                    if (act.isBottomNavVisible()) {
                        act.hideBottomBar();
                    } else {
                        act.showBottomBar();
                    }
                }
            });
        }

        // Tapping the Top Right Daemon Pill opens the Audio tab
        if (pillDaemonStatus != null) {
            pillDaemonStatus.setOnClickListener(v -> {
                if (getActivity() instanceof LauncherActivity) {
                    ((LauncherActivity) getActivity()).switchToAudioTab();
                }
            });
        }

        Context ctx = getContext();
        if (ctx != null) {
            daemonController = new DaemonController();
            prefs = ctx.getSharedPreferences("audioarch_prefs", Context.MODE_PRIVATE);
            float prefInterval = prefs.getFloat("stats_refresh_interval", 1.0f);
            PcStatsServer.getInstance().setIntervalSec(prefInterval);
            measuredRateHz = 1.0f / prefInterval;
        }

        // Ensure stats server is running
        PcStatsServer.getInstance().start(PcStatsServer.DEFAULT_PORT);

        // Populate initial UI with last cached stats if available
        PcStats last = PcStatsServer.getInstance().getLastStats();
        if (last != null) {
            updatePcStatsUi(last);
        }
        updateConnectionUi(PcStatsServer.getInstance().isConnected());

        // Daemon polling runnable (low frequency: every 2.5 seconds strictly from the phone)
        daemonPollRunnable = new Runnable() {
            @Override
            public void run() {
                if (isVisibleToUser && isAdded()) {
                    pollDaemonStatusAsync();
                    handler.postDelayed(this, 2500);
                }
            }
        };
    }

    @Override
    public void onDestroyView() {
        pbCores = null;
        cachedCoreProgress = null;
        viewFpsGraph = null;
        dividerNetGame = null;
        containerGame = null;
        super.onDestroyView();
    }

    @Override
    public void onResume() {
        super.onResume();
        isVisibleToUser = true;
        PcStatsServer.getInstance().addListener(this);

        // Immediate phone daemon check
        pollDaemonStatusAsync();
        handler.removeCallbacks(daemonPollRunnable);
        handler.postDelayed(daemonPollRunnable, 2500);

        if (PcStatsServer.getInstance().isCallMode()) {
            handler.removeCallbacks(pollPacketRunnable);
            handler.post(pollPacketRunnable);
        }
    }

    @Override
    public void onPause() {
        isVisibleToUser = false;
        isSmoothLoopActive = false;
        handler.removeCallbacks(smoothTransitionRunnable);
        handler.removeCallbacks(pollPacketRunnable);
        PcStatsServer.getInstance().removeListener(this);
        handler.removeCallbacks(daemonPollRunnable);
        super.onPause();
    }

    private final Runnable pollPacketRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isVisibleToUser || !isAdded()) return;
            if (PcStatsServer.getInstance().isCallMode() && PcStatsServer.getInstance().isConnected()) {
                PcStatsServer.getInstance().requestPacket();
                handler.postDelayed(this, 80); // ~12.5 Hz smooth request cadence
            }
        }
    };

    @Override
    public void onStatsReceived(PcStats stats) {
        if (!isAdded() || !isVisibleToUser) return;
        measuredRateHz = PcStatsServer.getInstance().getMeasuredRateHz();
        updateConnectionUi(true);
        updatePcStatsUi(stats);
    }

    @Override
    public void onConnectionChanged(boolean connected) {
        if (!isAdded() || !isVisibleToUser) return;
        updateConnectionUi(connected);
    }

    private void updateConnectionUi(boolean connected) {
        if (dotPcStatus != null) {
            dotPcStatus.setBackgroundResource(connected ? R.drawable.bg_status_running : R.drawable.bg_status_stopped);
        }
        if (tvPcStatus != null) {
            if (connected) {
                String rateLabel = getFormattedRate();
                tvPcStatus.setText("PC Linked (" + rateLabel + ")");
                tvPcStatus.setTextColor(0xFF00BCD4);
            } else {
                tvPcStatus.setText(R.string.stat_waiting_pc);
                tvPcStatus.setTextColor(0xFF888888);
            }
        }
    }

    private String getFormattedRate() {
        if (PcStatsServer.getInstance().isCallMode()) {
            return String.format(Locale.US, "Call %.0f Hz", measuredRateHz);
        } else if (measuredRateHz >= 100f) {
            return String.format(Locale.US, "%.0f Hz (Max)", measuredRateHz);
        } else if (measuredRateHz >= 40f) {
            return String.format(Locale.US, "%.0f Hz (Fast)", measuredRateHz);
        } else if (measuredRateHz >= 17f && measuredRateHz <= 23f) {
            return "20 Hz";
        } else if (measuredRateHz >= 8.5f && measuredRateHz <= 12f) {
            return "10 Hz";
        } else if (measuredRateHz >= 3.4f && measuredRateHz <= 4.6f) {
            return "4 Hz";
        } else if (measuredRateHz >= 1.7f && measuredRateHz <= 2.3f) {
            return "2 Hz";
        } else if (measuredRateHz >= 0.85f && measuredRateHz <= 1.25f) {
            return "1 Hz";
        } else if (measuredRateHz >= 0.42f && measuredRateHz <= 0.65f) {
            return "0.5 Hz";
        } else if (measuredRateHz >= 0.25f && measuredRateHz <= 0.41f) {
            return "0.33 Hz";
        } else if (measuredRateHz >= 10f) {
            return String.format(Locale.US, "%.0f Hz", measuredRateHz);
        } else {
            return String.format(Locale.US, "%.1f Hz", measuredRateHz);
        }
    }

    private void showRateSelectionDialog() {
        Context c = getContext();
        if (c == null) return;
        final SharedPreferences p = c.getSharedPreferences("audioarch_prefs", Context.MODE_PRIVATE);
        final String[] options = {
                "Call Mode (On-Demand / App Driven)",
                "0 ms (Uncapped - Fast as Possible)",
                "50 ms (20 Hz - Ultra Smooth)",
                "100 ms (10 Hz - Smooth Gaming)",
                "0.25s (4 Hz - Fast)",
                "0.5s (2 Hz - Balanced)",
                "1.0s (1 Hz - Standard / Default)",
                "2.0s (0.5 Hz - Low CPU)",
                "3.0s (0.33 Hz - Power Saver)"
        };
        final float[] values = {-1.0f, 0.0f, 0.05f, 0.10f, 0.25f, 0.5f, 1.0f, 2.0f, 3.0f};
        float current = p.getFloat("stats_refresh_interval", -1.0f);
        int selected = 0;
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] - current) < 0.02f) {
                selected = i;
                break;
            }
        }

        new AlertDialog.Builder(c)
                .setTitle("Telemetry Update Frequency")
                .setSingleChoiceItems(options, selected, (dialog, which) -> {
                    float chosen = values[which];
                    p.edit().putFloat("stats_refresh_interval", chosen).apply();
                    PcStatsServer.getInstance().setIntervalSec(chosen);
                    measuredRateHz = PcStatsServer.getInstance().getMeasuredRateHz();
                    handler.removeCallbacks(pollPacketRunnable);
                    if (PcStatsServer.getInstance().isCallMode()) {
                        handler.post(pollPacketRunnable);
                    }
                    updateConnectionUi(PcStatsServer.getInstance().isConnected());
                    dialog.dismiss();
                    Toast.makeText(c, "Stats rate set to " + options[which], Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private final Runnable smoothTransitionRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isVisibleToUser || !isAdded()) {
                isSmoothLoopActive = false;
                return;
            }

            boolean stillMoving = stepSmoothTransitions();
            if (stillMoving) {
                handler.postDelayed(this, 30);
            } else {
                isSmoothLoopActive = false;
            }
        }
    };

    private void startSmoothTransitionLoop() {
        if (!isSmoothLoopActive) {
            isSmoothLoopActive = true;
            handler.post(smoothTransitionRunnable);
        }
    }

    private boolean stepSmoothTransitions() {
        boolean active = false;

        // 1. CPU Usage: smooth rise (0.22), graceful fallback (0.06)
        if (Math.abs(targetCpuPct - currentCpuPct) > 0.3f) {
            active = true;
            if (targetCpuPct > currentCpuPct) {
                currentCpuPct += (targetCpuPct - currentCpuPct) * 0.22f;
            } else {
                currentCpuPct += (targetCpuPct - currentCpuPct) * 0.06f;
            }
        } else {
            currentCpuPct = targetCpuPct;
        }
        int cpuInt = Math.round(currentCpuPct);
        if (cpuInt != cachedCpuProgress && pbCpu != null) {
            cachedCpuProgress = cpuInt;
            pbCpu.setProgress(cpuInt);
        }
        String cpuStr = cpuInt + "%";
        if (!cpuStr.equals(cachedCpuPct) && tvCpuPct != null) {
            cachedCpuPct = cpuStr;
            tvCpuPct.setText(cpuStr);
        }

        // 2. CPU Core Bars: smooth rise (0.22), graceful fallback (0.06)
        if (targetCorePcts != null && pbCores != null) {
            if (currentCorePcts == null || currentCorePcts.length != targetCorePcts.length) {
                currentCorePcts = new float[targetCorePcts.length];
                for (int i = 0; i < targetCorePcts.length; i++) {
                    currentCorePcts[i] = targetCorePcts[i];
                }
            }
            for (int i = 0; i < targetCorePcts.length && i < pbCores.length; i++) {
                float t = targetCorePcts[i];
                float c = currentCorePcts[i];
                if (Math.abs(t - c) > 0.4f) {
                    active = true;
                    if (t > c) {
                        currentCorePcts[i] += (t - c) * 0.22f;
                    } else {
                        currentCorePcts[i] += (t - c) * 0.06f;
                    }
                } else {
                    currentCorePcts[i] = t;
                }
                int coreInt = Math.round(currentCorePcts[i]);
                if (pbCores[i] != null && cachedCoreProgress != null && i < cachedCoreProgress.length && cachedCoreProgress[i] != coreInt) {
                    cachedCoreProgress[i] = coreInt;
                    pbCores[i].setProgress(coreInt);
                }
            }
        }

        // 3. GPU Usage: smooth rise (0.22), graceful fallback (0.06)
        if (Math.abs(targetGpuPct - currentGpuPct) > 0.3f) {
            active = true;
            if (targetGpuPct > currentGpuPct) {
                currentGpuPct += (targetGpuPct - currentGpuPct) * 0.22f;
            } else {
                currentGpuPct += (targetGpuPct - currentGpuPct) * 0.06f;
            }
        } else {
            currentGpuPct = targetGpuPct;
        }
        int gpuInt = Math.round(currentGpuPct);
        if (gpuInt != cachedGpuProgress && pbGpu != null) {
            cachedGpuProgress = gpuInt;
            pbGpu.setProgress(gpuInt);
        }
        String gpuStr = "Core: " + gpuInt + "%";
        if (!gpuStr.equals(cachedGpuPct) && tvGpuPct != null) {
            cachedGpuPct = gpuStr;
            tvGpuPct.setText(gpuStr);
        }

        // 4. CPU Clock in kHz: smooth rise (0.20), graceful fallback (0.06)
        if (targetCpuKhz > 0) {
            float diff = targetCpuKhz - smoothCpuKhz;
            if (Math.abs(diff) > 1500f) {
                active = true;
                if (diff > 0) smoothCpuKhz += diff * 0.20f;
                else smoothCpuKhz += diff * 0.06f;
            } else {
                smoothCpuKhz = targetCpuKhz;
            }
            renderCpuClock(smoothCpuKhz);
        }

        // 5. GPU Core Clock in kHz: smooth rise (0.20), graceful fallback (0.06)
        if (targetGpuCoreKhz > 0) {
            float diff = targetGpuCoreKhz - smoothGpuCoreKhz;
            if (Math.abs(diff) > 1500f) {
                active = true;
                if (diff > 0) smoothGpuCoreKhz += diff * 0.20f;
                else smoothGpuCoreKhz += diff * 0.06f;
            } else {
                smoothGpuCoreKhz = targetGpuCoreKhz;
            }
            renderGpuCoreClock(smoothGpuCoreKhz);
        }

        // 6. GPU Memory Clock in kHz
        if (targetGpuMemKhz > 0) {
            float diff = targetGpuMemKhz - smoothGpuMemKhz;
            if (Math.abs(diff) > 1500f) {
                active = true;
                if (diff > 0) smoothGpuMemKhz += diff * 0.20f;
                else smoothGpuMemKhz += diff * 0.06f;
            } else {
                smoothGpuMemKhz = targetGpuMemKhz;
            }
            renderVramClock(smoothGpuMemKhz);
        }

        return active;
    }

    private void renderCpuClock(float khz) {
        if (tvCpuDetail == null) return;
        float ghz = khz / 1_000_000.0f;
        String text;
        if (ghz > 0.1f) {
            text = String.format(Locale.US, "%s@%.3fGHz", baseCpuName, ghz);
        } else {
            text = baseCpuName;
        }
        if (!text.equals(cachedCpuDetail)) {
            cachedCpuDetail = text;
            tvCpuDetail.setText(text);
        }
    }

    private void renderGpuCoreClock(float khz) {
        if (tvGpuDetail == null) return;
        float ghz = khz / 1_000_000.0f;
        String text;
        if (ghz > 0.05f) {
            text = String.format(Locale.US, "%s@%.3fGHz", baseGpuName, ghz);
        } else {
            text = baseGpuName;
        }
        if (!text.equals(cachedGpuDetail)) {
            cachedGpuDetail = text;
            tvGpuDetail.setText(text);
        }
    }

    private void renderVramClock(float khz) {
        if (tvVramDetail == null) return;
        float ghz = khz / 1_000_000.0f;
        String text;
        if (ghz > 0.05f) {
            text = String.format(Locale.US, "%.1f / %.1f GB @ %.3fGHz", lastVramUsed, lastVramTotal, ghz);
        } else {
            text = String.format(Locale.US, "%.1f / %.1f GB", lastVramUsed, lastVramTotal);
        }
        if (!text.equals(cachedVramDetail)) {
            cachedVramDetail = text;
            tvVramDetail.setText(text);
        }
    }

    private void setupCpuCoreBars(int count) {
        if (containerCpuCores == null || getContext() == null) return;
        containerCpuCores.removeAllViews();
        pbCores = new ProgressBar[count];
        cachedCoreProgress = new int[count];
        for (int i = 0; i < count; i++) {
            cachedCoreProgress[i] = -1;
        }

        int cols = (count <= 4) ? 2 : 3;
        int rows = (count + cols - 1) / cols;
        Context ctx = getContext();
        float density = ctx.getResources().getDisplayMetrics().density;

        int rowMarginTop = (int) (1.5f * density);
        int cellPaddingEnd = (int) (4f * density);
        int barHeight = (int) (3.5f * density);
        int labelWidth = (int) (12f * density);

        int coreIdx = 0;
        for (int r = 0; r < rows; r++) {
            LinearLayout rowLayout = new LinearLayout(ctx);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (r > 0) {
                rowLp.topMargin = rowMarginTop;
            }
            rowLayout.setLayoutParams(rowLp);

            for (int c = 0; c < cols; c++) {
                if (coreIdx < count) {
                    LinearLayout cellLayout = new LinearLayout(ctx);
                    cellLayout.setOrientation(LinearLayout.HORIZONTAL);
                    cellLayout.setGravity(Gravity.CENTER_VERTICAL);
                    LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
                    if (c < cols - 1) {
                        cellLp.rightMargin = cellPaddingEnd;
                    }
                    cellLayout.setLayoutParams(cellLp);

                    // Label: 1, 2, ... count
                    TextView tvLabel = new TextView(ctx);
                    tvLabel.setText(String.valueOf(coreIdx + 1));
                    tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
                    tvLabel.setTextColor(0xFF888888);
                    tvLabel.setLayoutParams(new LinearLayout.LayoutParams(
                            labelWidth, LinearLayout.LayoutParams.WRAP_CONTENT));
                    cellLayout.addView(tvLabel);

                    // Progress Bar
                    ProgressBar pb = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
                    pb.setMax(100);
                    pb.setProgress(0);
                    pb.setProgressDrawable(ContextCompat.getDrawable(ctx, R.drawable.bg_progress_cpu));
                    LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                            0, barHeight, 1.0f);
                    pb.setLayoutParams(pbLp);
                    cellLayout.addView(pb);

                    pbCores[coreIdx] = pb;
                    coreIdx++;
                    rowLayout.addView(cellLayout);
                } else {
                    // Empty spacer cell to keep column alignment
                    View spacer = new View(ctx);
                    LinearLayout.LayoutParams spacerLp = new LinearLayout.LayoutParams(0, 1, 1.0f);
                    if (c < cols - 1) {
                        spacerLp.rightMargin = cellPaddingEnd;
                    }
                    spacer.setLayoutParams(spacerLp);
                    rowLayout.addView(spacer);
                }
            }
            containerCpuCores.addView(rowLayout);
        }
    }

    private void updatePcStatsUi(PcStats stats) {
        if (stats == null) return;

        // Set targets for smooth animation & slow fallback loop
        targetCpuPct = stats.getCpuPct();
        targetCorePcts = stats.getCpuCorePcts();
        targetGpuPct = stats.getGpuPct();
        targetCpuKhz = stats.getCpuKhz();
        targetGpuCoreKhz = stats.getGpuCoreKhz();
        targetGpuMemKhz = stats.getGpuMemKhz();

        String cModel = stats.getCpuBaseModel();
        if (cModel != null && !cModel.isEmpty()) {
            baseCpuName = cModel;
        }
        String gModel = stats.getGpuBaseName();
        if (gModel != null && !gModel.isEmpty()) {
            baseGpuName = gModel;
        }

        lastVramUsed = stats.getVramUsedGb();
        lastVramTotal = stats.getVramTotalGb();

        // Initial values on first packet to avoid jumping from 0
        if (smoothCpuKhz <= 0 && targetCpuKhz > 0) smoothCpuKhz = targetCpuKhz;
        if (smoothGpuCoreKhz <= 0 && targetGpuCoreKhz > 0) smoothGpuCoreKhz = targetGpuCoreKhz;
        if (smoothGpuMemKhz <= 0 && targetGpuMemKhz > 0) smoothGpuMemKhz = targetGpuMemKhz;
        if (currentCpuPct <= 0 && targetCpuPct > 0) currentCpuPct = targetCpuPct;
        if (currentGpuPct <= 0 && targetGpuPct > 0) currentGpuPct = targetGpuPct;

        // 1. CPU Temp & Detail
        if (tvCpuTemp != null) {
            String cpuTempStr = stats.getCpuTemp() > 0 ? stats.getCpuTemp() + "\u00B0C" : "--\u00B0C";
            if (!cpuTempStr.equals(cachedCpuTemp)) {
                cachedCpuTemp = cpuTempStr;
                tvCpuTemp.setText(cpuTempStr);
            }
        }
        if (targetCpuKhz > 0) {
            renderCpuClock(smoothCpuKhz);
        } else {
            String cpuDetailStr = stats.getCpuModel();
            if (!cpuDetailStr.equals(cachedCpuDetail) && tvCpuDetail != null) {
                cachedCpuDetail = cpuDetailStr;
                tvCpuDetail.setText(cpuDetailStr);
            }
        }

        // Per-Core Bars (2 or 3 Columns)
        int[] corePcts = stats.getCpuCorePcts();
        if (corePcts != null && corePcts.length > 0) {
            if (pbCpu != null) pbCpu.setVisibility(View.GONE);
            if (containerCpuCores != null) containerCpuCores.setVisibility(View.VISIBLE);

            if (pbCores == null || pbCores.length != corePcts.length) {
                setupCpuCoreBars(corePcts.length);
            }
        } else {
            // Fallback to overall bar if per-core stats are not present
            if (containerCpuCores != null) containerCpuCores.setVisibility(View.GONE);
            if (pbCpu != null) pbCpu.setVisibility(View.VISIBLE);
        }

        // 2. RAM & DISK
        int ram = stats.getRamPct();
        if (ram != cachedRamProgress && pbRam != null) {
            cachedRamProgress = ram;
            pbRam.setProgress(ram);
        }
        String ramPctStr = ram + "%";
        if (!ramPctStr.equals(cachedRamPct) && tvRamPct != null) {
            cachedRamPct = ramPctStr;
            tvRamPct.setText(ramPctStr);
        }
        String ramDetailStr = String.format(Locale.US, "%.1f / %.1f GB", stats.getRamUsedGb(), stats.getRamTotalGb());
        if (!ramDetailStr.equals(cachedRamDetail) && tvRamDetail != null) {
            cachedRamDetail = ramDetailStr;
            tvRamDetail.setText(ramDetailStr);
        }

        // Disk (Root)
        int disk = stats.getDiskPct();
        if (disk != cachedDiskProgress && pbDisk != null) {
            cachedDiskProgress = disk;
            pbDisk.setProgress(disk);
        }
        String diskPctStr = disk + "%";
        if (!diskPctStr.equals(cachedDiskPct) && tvDiskPct != null) {
            cachedDiskPct = diskPctStr;
            tvDiskPct.setText(diskPctStr);
        }
        String diskDetailStr = String.format(Locale.US, "Root: %.1f / %.1f GB", stats.getDiskUsedGb(), stats.getDiskTotalGb());
        if (!diskDetailStr.equals(cachedDiskDetail) && tvDiskDetail != null) {
            cachedDiskDetail = diskDetailStr;
            tvDiskDetail.setText(diskDetailStr);
        }

        // 3. GPU (Core & VRAM)
        if (tvGpuTemp != null) {
            String gpuTempStr = stats.getGpuTemp() > 0 ? stats.getGpuTemp() + "\u00B0C" : "--\u00B0C";
            if (!gpuTempStr.equals(cachedGpuTemp)) {
                cachedGpuTemp = gpuTempStr;
                tvGpuTemp.setText(gpuTempStr);
            }
        }

        // VRAM Bar & Detail
        int vram = stats.getVramPct();
        if (vram != cachedVramProgress && pbVram != null) {
            cachedVramProgress = vram;
            pbVram.setProgress(vram);
        }
        String vramPctStr = vram + "%";
        if (!vramPctStr.equals(cachedVramPct) && tvVramPct != null) {
            cachedVramPct = vramPctStr;
            tvVramPct.setText(vramPctStr);
        }
        if (targetGpuMemKhz > 0) {
            renderVramClock(smoothGpuMemKhz);
        } else {
            String vramDetailStr;
            if (stats.getGpuMemGhz() > 0.05f) {
                vramDetailStr = String.format(Locale.US, "%.1f / %.1f GB @ %.3fGHz",
                        stats.getVramUsedGb(), stats.getVramTotalGb(), stats.getGpuMemGhz());
            } else {
                vramDetailStr = String.format(Locale.US, "%.1f / %.1f GB",
                        stats.getVramUsedGb(), stats.getVramTotalGb());
            }
            if (!vramDetailStr.equals(cachedVramDetail) && tvVramDetail != null) {
                cachedVramDetail = vramDetailStr;
                tvVramDetail.setText(vramDetailStr);
            }
        }

        // GPU Model Name (fallback if no dynamic clock)
        if (targetGpuCoreKhz > 0) {
            renderGpuCoreClock(smoothGpuCoreKhz);
        } else {
            String gpuModel = stats.getGpuName();
            if (!gpuModel.equals(cachedGpuDetail) && tvGpuDetail != null) {
                cachedGpuDetail = gpuModel;
                tvGpuDetail.setText(gpuModel);
            }
        }

        // Kick off smooth transition loop for CPU%, GPU%, core bars, and frequencies
        startSmoothTransitionLoop();

        // 4. Network Speed & IP / Link Info (Bottom Right)
        float downKb = stats.getNetDownKbps();
        float upKb = stats.getNetUpKbps();
        String summaryStr = "\u2193 " + PcStats.formatSpeed(downKb) + "   \u2191 " + PcStats.formatSpeed(upKb);

        if (!summaryStr.equals(cachedNetSummary) && tvNetSpeedSummary != null) {
            cachedNetSummary = summaryStr;
            tvNetSpeedSummary.setText(summaryStr);
        }

        // Dedicated Line 2: IP Address
        if (tvNetIp != null) {
            String ip = stats.getNetIp();
            String ipStr = (ip != null && !ip.isEmpty()) ? ("IP: " + ip) : "IP: --";
            if (!ipStr.equals(cachedNetIp)) {
                cachedNetIp = ipStr;
                tvNetIp.setText(ipStr);
            }
        }

        // Dedicated Line 3: Network Name & Link Speed
        if (tvNetLink != null) {
            String link = stats.getNetLink();
            String linkStr = (link != null && !link.isEmpty()) ? ("Link: " + link) : "Link: --";
            if (!linkStr.equals(cachedNetLink)) {
                cachedNetLink = linkStr;
                tvNetLink.setText(linkStr);
            }
        }

        // 5. Dynamic Game Slot (Visible ONLY when a game is running)
        boolean hasGame = stats.isGameRunning();
        if (hasGame) {
            if (dividerNetGame != null && dividerNetGame.getVisibility() != View.VISIBLE) {
                dividerNetGame.setVisibility(View.VISIBLE);
            }
            if (containerGame != null && containerGame.getVisibility() != View.VISIBLE) {
                containerGame.setVisibility(View.VISIBLE);
            }

            String gameName = stats.getGameName();
            if (!gameName.equals(cachedGameName) && tvGameName != null) {
                cachedGameName = gameName;
                tvGameName.setText(gameName);
            }

            int fps = stats.getGameFps();
            if (fps > 0) {
                String fpsStr = fps + " FPS";
                if (!fpsStr.equals(cachedGameFps) && tvGameFps != null) {
                    cachedGameFps = fpsStr;
                    tvGameFps.setText(fpsStr);
                }

                if (viewFpsGraph != null) {
                    if (viewFpsGraph.getVisibility() != View.VISIBLE) {
                        viewFpsGraph.setVisibility(View.VISIBLE);
                    }
                    viewFpsGraph.addFps(fps);
                }
            } else {
                String fpsStr = "-- FPS";
                if (!fpsStr.equals(cachedGameFps) && tvGameFps != null) {
                    cachedGameFps = fpsStr;
                    tvGameFps.setText(fpsStr);
                }

                // Hide the graph when there is no FPS telemetry so it doesn't show a null baseline
                if (viewFpsGraph != null && viewFpsGraph.getVisibility() != View.GONE) {
                    viewFpsGraph.setVisibility(View.GONE);
                }
            }
        } else {
            if (dividerNetGame != null && dividerNetGame.getVisibility() != View.GONE) {
                dividerNetGame.setVisibility(View.GONE);
            }
            if (containerGame != null && containerGame.getVisibility() != View.GONE) {
                containerGame.setVisibility(View.GONE);
            }
            cachedGameName = "";
            cachedGameFps = "";
            if (viewFpsGraph != null) {
                viewFpsGraph.clear();
            }
        }
    }

    /**
     * Polls active daemon status strictly from the phone's internal process state.
     * The PC never supplies or overrides this info.
     */
    private void pollDaemonStatusAsync() {
        if (daemonController == null || prefs == null) return;
        int port = prefs.getInt("port", DaemonController.DEFAULT_PORT);
        new CheckDaemonTask(this, daemonController, port).execute();
    }

    private static class CheckDaemonTask extends AsyncTask<Void, Void, DaemonCheckResult> {
        private final WeakReference<StatsFragment> fragRef;
        private final DaemonController controller;
        private final int port;

        CheckDaemonTask(StatsFragment frag, DaemonController controller, int port) {
            this.fragRef = new WeakReference<>(frag);
            this.controller = controller;
            this.port = port;
        }

        @Override
        protected DaemonCheckResult doInBackground(Void... voids) {
            DaemonStatus cStatus = controller.checkCDaemon(port);
            DaemonStatus btStatus = controller.checkBtDaemon(port);
            String ver = controller.getDaemonVersion();
            return new DaemonCheckResult(cStatus, btStatus, ver);
        }

        @Override
        protected void onPostExecute(DaemonCheckResult result) {
            StatsFragment frag = fragRef.get();
            if (frag != null && frag.isAdded() && result != null) {
                frag.onDaemonCheckComplete(result);
            }
        }
    }

    private static class DaemonCheckResult {
        final DaemonStatus cStatus;
        final DaemonStatus btStatus;
        final String version;

        DaemonCheckResult(DaemonStatus cStatus, DaemonStatus btStatus, String version) {
            this.cStatus = cStatus;
            this.btStatus = btStatus;
            this.version = (version != null && !version.trim().isEmpty()) ? version.trim() : "1.3.3";
        }
    }

    private void onDaemonCheckComplete(DaemonCheckResult res) {
        boolean isCRunning = res.cStatus != null && res.cStatus.isRunning();
        boolean isBtRunning = res.btStatus != null && res.btStatus.isRunning();
        String ver = res.version.startsWith("v") ? res.version : "v" + res.version;

        if (isCRunning) {
            updateDaemonPill("ALSA " + ver, true);
        } else if (isBtRunning) {
            updateDaemonPill("AudioTrack " + ver, true);
        } else {
            updateDaemonPill("Daemon: Off", false);
        }
    }

    private void updateDaemonPill(String title, boolean running) {
        if (dotDaemonPill != null) {
            dotDaemonPill.setBackgroundResource(running ? R.drawable.bg_status_running : R.drawable.bg_status_stopped);
        }
        if (tvDaemonPill != null) {
            tvDaemonPill.setText(title);
            tvDaemonPill.setTextColor(running ? 0xFF4CAF50 : 0xFF888888);
        }
    }
}
