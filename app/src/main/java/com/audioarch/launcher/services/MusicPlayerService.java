package com.audioarch.launcher.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.audioarch.launcher.LauncherActivity;
import com.audioarch.launcher.R;
import com.audioarch.launcher.models.Song;

import java.util.ArrayList;
import java.util.List;

public class MusicPlayerService extends Service {

    public static final String ACTION_PLAY_PAUSE = "com.audioarch.launcher.PLAY_PAUSE";
    public static final String ACTION_NEXT = "com.audioarch.launcher.NEXT";
    public static final String ACTION_PREV = "com.audioarch.launcher.PREV";
    public static final String BROADCAST_STATE_CHANGED = "com.audioarch.launcher.STATE_CHANGED";

    private static final String CHANNEL_ID = "audioarch_playback";
    private static final int NOTIFICATION_ID = 1;

    private final IBinder binder = new MusicBinder();
    private MediaPlayer mediaPlayer;
    private List<Song> playlist = new ArrayList<>();
    private int currentIndex = -1;
    private boolean isPlaying = false;

    public class MusicBinder extends Binder {
        public MusicPlayerService getService() {
            return MusicPlayerService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                playNext();
            }
        });
        mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                mp.reset();
                isPlaying = false;
                broadcastStateChange();
                return true;
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_PLAY_PAUSE:
                    playPause();
                    break;
                case ACTION_NEXT:
                    playNext();
                    break;
                case ACTION_PREV:
                    playPrev();
                    break;
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        super.onDestroy();
    }

    public void setPlaylist(List<Song> songs, int startIndex) {
        this.playlist = new ArrayList<>(songs);
        this.currentIndex = startIndex;
        playCurrent();
    }

    public void playCurrent() {
        if (playlist.isEmpty() || currentIndex < 0 || currentIndex >= playlist.size()) {
            return;
        }

        try {
            mediaPlayer.reset();
            mediaPlayer.setDataSource(playlist.get(currentIndex).getPath());
            mediaPlayer.prepare();
            mediaPlayer.start();
            isPlaying = true;
            showNotification();
            broadcastStateChange();
        } catch (Exception e) {
            e.printStackTrace();
            isPlaying = false;
            broadcastStateChange();
        }
    }

    public void playPause() {
        if (mediaPlayer == null) return;

        if (isPlaying) {
            mediaPlayer.pause();
            isPlaying = false;
        } else {
            mediaPlayer.start();
            isPlaying = true;
        }
        showNotification();
        broadcastStateChange();
    }

    public void playNext() {
        if (playlist.isEmpty()) return;
        currentIndex = (currentIndex + 1) % playlist.size();
        playCurrent();
    }

    public void playPrev() {
        if (playlist.isEmpty()) return;
        currentIndex = currentIndex - 1;
        if (currentIndex < 0) currentIndex = playlist.size() - 1;
        playCurrent();
    }

    public Song getCurrentSong() {
        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            return playlist.get(currentIndex);
        }
        return null;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public int getCurrentPosition() {
        try {
            return mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public int getDuration() {
        try {
            return mediaPlayer != null ? mediaPlayer.getDuration() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public void seekTo(int position) {
        if (mediaPlayer != null) {
            mediaPlayer.seekTo(position);
        }
    }

    public List<Song> getPlaylist() {
        return playlist;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    private void broadcastStateChange() {
        Intent intent = new Intent(BROADCAST_STATE_CHANGED);
        intent.putExtra("isPlaying", isPlaying);
        intent.putExtra("currentIndex", currentIndex);
        Song song = getCurrentSong();
        if (song != null) {
            intent.putExtra("title", song.getTitle());
            intent.putExtra("artist", song.getArtist());
        }
        sendBroadcast(intent);
    }

    private void showNotification() {
        Song song = getCurrentSong();
        if (song == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Audio Playback", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        Intent openIntent = new Intent(this, LauncherActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent, 0);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music)
                .setContentTitle(song.getTitle())
                .setContentText(song.getArtist())
                .setContentIntent(pendingIntent)
                .setOngoing(isPlaying)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, notification);
        }

        if (isPlaying) {
            startForeground(NOTIFICATION_ID, notification);
        } else {
            stopForeground(false);
        }
    }
}
