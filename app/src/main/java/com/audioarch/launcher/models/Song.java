package com.audioarch.launcher.models;

import android.net.Uri;

public class Song {
    private final long id;
    private final String title;
    private final String artist;
    private final String album;
    private final long albumId;
    private final long duration;
    private final String path;
    private final Uri albumArtUri;

    public Song(long id, String title, String artist, String album, long albumId, long duration, String path, Uri albumArtUri) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.albumId = albumId;
        this.duration = duration;
        this.path = path;
        this.albumArtUri = albumArtUri;
    }

    public long getId() { return id; }
    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public String getAlbum() { return album; }
    public long getAlbumId() { return albumId; }
    public long getDuration() { return duration; }
    public String getPath() { return path; }
    public Uri getAlbumArtUri() { return albumArtUri; }

    public String getFormattedDuration() {
        long totalSecs = duration / 1000;
        long mins = totalSecs / 60;
        long secs = totalSecs % 60;
        return String.format("%d:%02d", mins, secs);
    }
}
