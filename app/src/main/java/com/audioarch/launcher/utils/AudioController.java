package com.audioarch.launcher.utils;

import android.content.Context;
import android.media.AudioManager;

public class AudioController {

    private final AudioManager audioManager;

    public AudioController(Context context) {
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public int getMaxVolume() {
        return audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    }

    public int getCurrentVolume() {
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
    }

    public void setVolume(int volume) {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
    }

    public int getVolumePercentage() {
        int max = getMaxVolume();
        if (max == 0) return 0;
        return (getCurrentVolume() * 100) / max;
    }

    public boolean isBluetoothAudioConnected() {
        return audioManager.isBluetoothA2dpOn();
    }

    @SuppressWarnings("deprecation")
    public boolean isWiredHeadsetOn() {
        return audioManager.isWiredHeadsetOn();
    }

    public String getActiveAudioRoute() {
        if (isBluetoothAudioConnected()) {
            return "Bluetooth";
        } else if (isWiredHeadsetOn()) {
            return "Headphones";
        } else {
            return "Speaker";
        }
    }
}
