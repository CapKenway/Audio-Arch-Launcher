package com.audioarch.launcher;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;

import com.audioarch.launcher.fragments.DashboardFragment;
import com.audioarch.launcher.fragments.MusicFragment;
import com.audioarch.launcher.fragments.SettingsFragment;
import com.audioarch.launcher.models.Song;
import com.audioarch.launcher.services.MusicPlayerService;
import com.audioarch.launcher.views.NonSwipeableViewPager;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.io.InputStream;
import java.lang.ref.WeakReference;

public class LauncherActivity extends AppCompatActivity {

    private NonSwipeableViewPager viewPager;
    private BottomNavigationView bottomNav;
    private View bottomContainer;
    private View swipeHandle;
    private View nowPlayingBar;
    private ImageView npAlbumArt;
    private ImageButton npPlayPause, npPrev, npNext;
    private TextView npTitle, npArtist;

    private MusicPlayerService musicService;
    private boolean serviceBound = false;

    private boolean isBottomBarVisible = true;
    private final Handler autoHideHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoHideRunnable = this::hideBottomBar;

    private float touchDownY = 0;
    private float touchDownX = 0;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MusicPlayerService.MusicBinder binder = (MusicPlayerService.MusicBinder) service;
            musicService = binder.getService();
            serviceBound = true;
            updateNowPlayingBar();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            musicService = null;
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateNowPlayingBar();
        }
    };

    static {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Hide notification / status bar across all Android versions
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        applyImmersiveFullscreen();

        setContentView(R.layout.activity_launcher);

        viewPager = findViewById(R.id.view_pager);
        bottomNav = findViewById(R.id.bottom_navigation);
        bottomContainer = findViewById(R.id.bottom_container);
        swipeHandle = findViewById(R.id.swipe_handle);
        nowPlayingBar = findViewById(R.id.now_playing_bar);

        // 2. Disable horizontal page swiping (switch pages via bottom navigation only)
        viewPager.setSwipeEnabled(false);

        // Now playing views
        npAlbumArt = nowPlayingBar.findViewById(R.id.np_album_art);
        npPlayPause = nowPlayingBar.findViewById(R.id.np_play_pause);
        npPrev = nowPlayingBar.findViewById(R.id.np_prev);
        npNext = nowPlayingBar.findViewById(R.id.np_next);
        npTitle = nowPlayingBar.findViewById(R.id.np_title);
        npArtist = nowPlayingBar.findViewById(R.id.np_artist);

        // Setup ViewPager
        SectionsPagerAdapter pagerAdapter = new SectionsPagerAdapter(getSupportFragmentManager());
        viewPager.setAdapter(pagerAdapter);
        viewPager.setOffscreenPageLimit(2);

        // Sync bottom nav with ViewPager
        bottomNav.setOnNavigationItemSelectedListener(item -> {
            resetAutoHideTimer();
            int id = item.getItemId();
            if (id == R.id.nav_dashboard) {
                viewPager.setCurrentItem(0, false);
                return true;
            } else if (id == R.id.nav_music) {
                viewPager.setCurrentItem(1, false);
                return true;
            } else if (id == R.id.nav_settings) {
                viewPager.setCurrentItem(2, false);
                return true;
            }
            return false;
        });

        // Tapping active tab acts as hidden shortcut to hide the bottom nav bar
        bottomNav.setOnNavigationItemReselectedListener(item -> {
            hideBottomBar();
        });

        // Dedicated hidden / discreet tuck handle button at top of bottom nav bar
        View btnHideBottomNav = findViewById(R.id.btn_hide_bottom_nav);
        if (btnHideBottomNav != null) {
            btnHideBottomNav.setOnClickListener(v -> hideBottomBar());
        }

        // Tap swipe handle to bring up bottom bar
        if (swipeHandle != null) {
            swipeHandle.setOnClickListener(v -> showBottomBar());
        }

        // Now playing controls
        npPlayPause.setOnClickListener(v -> {
            resetAutoHideTimer();
            if (serviceBound && musicService != null) {
                musicService.playPause();
            }
        });
        npNext.setOnClickListener(v -> {
            resetAutoHideTimer();
            if (serviceBound && musicService != null) {
                musicService.playNext();
            }
        });
        npPrev.setOnClickListener(v -> {
            resetAutoHideTimer();
            if (serviceBound && musicService != null) {
                musicService.playPrev();
            }
        });

        // Start and bind to music service
        Intent serviceIntent = new Intent(this, MusicPlayerService.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        // Auto-tuck bottom bar after 4 seconds initially
        resetAutoHideTimer();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveFullscreen();
        }
    }

    private void applyImmersiveFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                );
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Intercepts touch events to detect swipe-up from the bottom to reveal the navigation bar,
     * and swipe-down near the bottom to tuck it away.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownY = ev.getRawY();
                touchDownX = ev.getRawX();
                if (isBottomBarVisible) {
                    resetAutoHideTimer();
                }
                break;

            case MotionEvent.ACTION_UP:
                float deltaY = touchDownY - ev.getRawY(); // positive = swiped UP
                float deltaX = Math.abs(ev.getRawX() - touchDownX);
                int screenHeight = getResources().getDisplayMetrics().heightPixels;

                // Swipe UP gesture from the bottom area (bottom 35% of the screen)
                if (deltaY > 50 && Math.abs(deltaY) > deltaX) {
                    if (touchDownY > screenHeight * 0.65f) {
                        showBottomBar();
                    }
                } else if (deltaY < -50 && Math.abs(deltaY) > deltaX) {
                    // Swipe DOWN near bottom bar to dismiss
                    if (touchDownY > screenHeight * 0.65f && isBottomBarVisible) {
                        hideBottomBar();
                    }
                }
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    public void showBottomBar() {
        if (!isBottomBarVisible) {
            isBottomBarVisible = true;
            if (bottomContainer != null) {
                bottomContainer.setVisibility(View.VISIBLE);
                bottomContainer.animate()
                        .translationY(0)
                        .alpha(1.0f)
                        .setDuration(220)
                        .start();
            }
            if (swipeHandle != null) {
                swipeHandle.animate().alpha(0.0f).setDuration(180).start();
            }
        }
        resetAutoHideTimer();
    }

    public void hideBottomBar() {
        if (isBottomBarVisible) {
            isBottomBarVisible = false;
            autoHideHandler.removeCallbacks(autoHideRunnable);
            if (bottomContainer != null) {
                int height = bottomContainer.getHeight();
                if (height <= 0) height = 300;
                bottomContainer.animate()
                        .translationY(height)
                        .alpha(0.0f)
                        .setDuration(220)
                        .withEndAction(() -> {
                            if (!isBottomBarVisible && bottomContainer != null) {
                                bottomContainer.setVisibility(View.GONE);
                            }
                        })
                        .start();
            }
            if (swipeHandle != null) {
                swipeHandle.animate().alpha(1.0f).setDuration(180).start();
            }
        }
    }

    private void resetAutoHideTimer() {
        autoHideHandler.removeCallbacks(autoHideRunnable);
        autoHideHandler.postDelayed(autoHideRunnable, 4500); // 4.5 seconds
    }

    @Override
    protected void onStart() {
        super.onStart();
        try {
            registerReceiver(stateReceiver, new IntentFilter(MusicPlayerService.BROADCAST_STATE_CHANGED));
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onStop() {
        try {
            unregisterReceiver(stateReceiver);
        } catch (Throwable ignored) {}
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        autoHideHandler.removeCallbacks(autoHideRunnable);
        if (serviceBound) {
            try {
                unbindService(serviceConnection);
            } catch (Throwable ignored) {}
            serviceBound = false;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // If bottom bar is visible, back button tucks it away; otherwise move task to back
        if (isBottomBarVisible) {
            hideBottomBar();
        } else {
            moveTaskToBack(true);
        }
    }

    public boolean isBottomNavVisible() {
        return isBottomBarVisible;
    }

    public MusicPlayerService getMusicService() {
        return serviceBound ? musicService : null;
    }

    public void updateNowPlaying() {
        updateNowPlayingBar();
    }

    private void updateNowPlayingBar() {
        if (serviceBound && musicService != null && musicService.getCurrentSong() != null) {
            nowPlayingBar.setVisibility(View.VISIBLE);
            Song song = musicService.getCurrentSong();
            npTitle.setText(song.getTitle());
            npArtist.setText(song.getArtist());
            npTitle.setSelected(true); // enable marquee

            if (musicService.isPlaying()) {
                npPlayPause.setImageResource(R.drawable.ic_pause);
            } else {
                npPlayPause.setImageResource(R.drawable.ic_play);
            }

            // Load album art
            if (song.getAlbumArtUri() != null) {
                new NowPlayingArtLoader(npAlbumArt, getContentResolver())
                        .execute(song.getAlbumArtUri());
            } else {
                npAlbumArt.setImageResource(R.drawable.ic_album_placeholder);
            }

            // If a song starts, bring up bottom bar briefly so user sees the now playing bar
            showBottomBar();
        } else {
            nowPlayingBar.setVisibility(View.GONE);
        }
    }

    private static class NowPlayingArtLoader extends AsyncTask<Uri, Void, Bitmap> {
        private final WeakReference<ImageView> ref;
        private final android.content.ContentResolver cr;

        NowPlayingArtLoader(ImageView iv, android.content.ContentResolver cr) {
            this.ref = new WeakReference<>(iv);
            this.cr = cr;
        }

        @Override
        protected Bitmap doInBackground(Uri... uris) {
            try {
                InputStream is = cr.openInputStream(uris[0]);
                if (is != null) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 4;
                    opts.inPreferredConfig = Bitmap.Config.RGB_565;
                    Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
                    is.close();
                    return bmp;
                }
            } catch (Throwable ignored) {}
            return null;
        }

        @Override
        protected void onPostExecute(Bitmap bmp) {
            ImageView iv = ref.get();
            if (iv != null && bmp != null) iv.setImageBitmap(bmp);
        }
    }

    @SuppressWarnings("deprecation")
    private static class SectionsPagerAdapter extends FragmentPagerAdapter {
        SectionsPagerAdapter(FragmentManager fm) {
            super(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT);
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            switch (position) {
                case 0: return new DashboardFragment();
                case 1: return new MusicFragment();
                case 2: return new SettingsFragment();
                default: return new DashboardFragment();
            }
        }

        @Override
        public int getCount() {
            return 3;
        }
    }
}
