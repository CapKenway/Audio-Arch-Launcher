package com.audioarch.launcher.fragments;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.audioarch.launcher.LauncherActivity;
import com.audioarch.launcher.R;
import com.audioarch.launcher.adapters.AlbumAdapter;
import com.audioarch.launcher.adapters.SongAdapter;
import com.audioarch.launcher.models.Song;
import com.audioarch.launcher.services.MusicPlayerService;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MusicFragment extends Fragment {

    private TabLayout musicTabs;
    private RecyclerView songsList, albumsGrid;
    private TextView emptyState;

    private SongAdapter songAdapter;
    private AlbumAdapter albumAdapter;
    private List<Song> allSongs = new ArrayList<>();
    private List<AlbumAdapter.Album> allAlbums = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_music, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        musicTabs = view.findViewById(R.id.music_tabs);
        songsList = view.findViewById(R.id.songs_list);
        albumsGrid = view.findViewById(R.id.albums_grid);
        emptyState = view.findViewById(R.id.empty_state);

        // Setup tabs
        musicTabs.addTab(musicTabs.newTab().setText(R.string.songs));
        musicTabs.addTab(musicTabs.newTab().setText(R.string.albums));

        musicTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (tab.getPosition() == 0) {
                    songsList.setVisibility(View.VISIBLE);
                    albumsGrid.setVisibility(View.GONE);
                } else {
                    songsList.setVisibility(View.GONE);
                    albumsGrid.setVisibility(View.VISIBLE);
                }
            }
            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}
            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });

        // Songs RecyclerView
        songsList.setLayoutManager(new LinearLayoutManager(requireContext()));
        songAdapter = new SongAdapter(requireContext(), allSongs, new SongAdapter.OnSongClickListener() {
            @Override
            public void onSongClick(int position, Song song) {
                playSong(position);
            }
        });
        songsList.setAdapter(songAdapter);

        // Albums RecyclerView
        albumsGrid.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        albumAdapter = new AlbumAdapter(requireContext(), allAlbums, new AlbumAdapter.OnAlbumClickListener() {
            @Override
            public void onAlbumClick(AlbumAdapter.Album album) {
                filterByAlbum(album);
            }
        });
        albumsGrid.setAdapter(albumAdapter);

        // Load music
        loadMusic();
    }

    private void playSong(int position) {
        if (getActivity() instanceof LauncherActivity) {
            LauncherActivity activity = (LauncherActivity) getActivity();
            MusicPlayerService service = activity.getMusicService();
            if (service != null) {
                service.setPlaylist(songAdapter.getSongs(), position);
                activity.updateNowPlaying();
            }
        }
    }

    private void filterByAlbum(AlbumAdapter.Album album) {
        List<Song> filtered = new ArrayList<>();
        for (Song song : allSongs) {
            if (song.getAlbumId() == album.getAlbumId()) {
                filtered.add(song);
            }
        }
        songAdapter.updateSongs(filtered);
        // Switch to songs tab
        TabLayout.Tab songsTab = musicTabs.getTabAt(0);
        if (songsTab != null) songsTab.select();
    }

    @SuppressWarnings("deprecation")
    private void loadMusic() {
        final Context ctx = getContext();
        if (ctx == null) return;
        final android.content.ContentResolver resolver = ctx.getContentResolver();

        new AsyncTask<Void, Void, Object[]>() {
            @Override
            protected Object[] doInBackground(Void... voids) {
                List<Song> songs = new ArrayList<>();
                List<AlbumAdapter.Album> albums = new ArrayList<>();

                if (resolver == null) return new Object[]{songs, albums};

                Uri musicUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                String[] projection = {
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.ALBUM,
                        MediaStore.Audio.Media.ALBUM_ID,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.DATA
                };

                String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
                String sortOrder = MediaStore.Audio.Media.TITLE + " ASC";

                Cursor cursor = null;
                try {
                    cursor = resolver.query(musicUri, projection, selection, null, sortOrder);

                    if (cursor != null) {
                        int idIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                        int titleIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                        int artistIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                        int albumIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                        int albumIdIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                        int durationIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                        int dataIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);

                        Map<Long, AlbumAdapter.Album> albumMap = new LinkedHashMap<>();

                        while (cursor.moveToNext()) {
                            long id = cursor.getLong(idIdx);
                            String title = cursor.getString(titleIdx);
                            String artist = cursor.getString(artistIdx);
                            String album = cursor.getString(albumIdx);
                            long albumId = cursor.getLong(albumIdIdx);
                            long duration = cursor.getLong(durationIdx);
                            String path = cursor.getString(dataIdx);

                            if (title == null) title = "Unknown";
                            if (artist == null || "<unknown>".equals(artist)) artist = "Unknown Artist";
                            if (album == null) album = "Unknown Album";

                            Uri albumArtUri = ContentUris.withAppendedId(
                                    Uri.parse("content://media/external/audio/albumart"), albumId);

                            songs.add(new Song(id, title, artist, album, albumId, duration, path, albumArtUri));

                            if (!albumMap.containsKey(albumId)) {
                                albumMap.put(albumId, new AlbumAdapter.Album(
                                        albumId, album, artist, 1, albumArtUri));
                            }
                        }

                        albums.addAll(albumMap.values());
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                } finally {
                    if (cursor != null) {
                        try { cursor.close(); } catch (Throwable ignored) {}
                    }
                }

                return new Object[]{songs, albums};
            }

            @Override
            @SuppressWarnings("unchecked")
            protected void onPostExecute(Object[] results) {
                if (!isAdded() || getContext() == null || results == null) return;

                try {
                    allSongs = (List<Song>) results[0];
                    allAlbums = (List<AlbumAdapter.Album>) results[1];

                    if (songAdapter != null) songAdapter.updateSongs(allSongs);
                    if (albumAdapter != null) albumAdapter.updateAlbums(allAlbums);

                    if (emptyState != null && songsList != null) {
                        if (allSongs.isEmpty()) {
                            emptyState.setVisibility(View.VISIBLE);
                            songsList.setVisibility(View.GONE);
                        } else {
                            emptyState.setVisibility(View.GONE);
                            songsList.setVisibility(View.VISIBLE);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
}
