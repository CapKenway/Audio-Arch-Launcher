package com.audioarch.launcher.adapters;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.audioarch.launcher.R;
import com.audioarch.launcher.models.Song;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SongAdapter extends RecyclerView.Adapter<SongAdapter.ViewHolder> {

    public interface OnSongClickListener {
        void onSongClick(int position, Song song);
    }

    private final Context context;
    private List<Song> songs;
    private List<Song> allSongs;
    private final OnSongClickListener listener;

    public SongAdapter(Context context, List<Song> songs, OnSongClickListener listener) {
        this.context = context;
        this.songs = new ArrayList<>(songs);
        this.allSongs = new ArrayList<>(songs);
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_song, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final Song song = songs.get(position);
        holder.songTitle.setText(song.getTitle());

        String subtitle = song.getArtist();
        if (song.getAlbum() != null && !song.getAlbum().isEmpty()) {
            subtitle += " \u2022 " + song.getAlbum();
        }
        holder.songArtist.setText(subtitle);
        holder.songDuration.setText(song.getFormattedDuration());

        // Reset to placeholder
        holder.albumArt.setImageResource(R.drawable.ic_album_placeholder);

        // Load album art async
        if (song.getAlbumArtUri() != null) {
            new AlbumArtLoader(holder.albumArt, context.getContentResolver())
                    .execute(song.getAlbumArtUri());
        }

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    int pos = holder.getAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) {
                        listener.onSongClick(pos, songs.get(pos));
                    }
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return songs.size();
    }

    public void filter(String query) {
        if (query == null || query.trim().isEmpty()) {
            songs = new ArrayList<>(allSongs);
        } else {
            String lowerQuery = query.toLowerCase(Locale.getDefault());
            songs = new ArrayList<>();
            for (Song song : allSongs) {
                if ((song.getTitle() != null && song.getTitle().toLowerCase(Locale.getDefault()).contains(lowerQuery)) ||
                    (song.getArtist() != null && song.getArtist().toLowerCase(Locale.getDefault()).contains(lowerQuery))) {
                    songs.add(song);
                }
            }
        }
        notifyDataSetChanged();
    }

    public void updateSongs(List<Song> newSongs) {
        this.songs = new ArrayList<>(newSongs);
        this.allSongs = new ArrayList<>(newSongs);
        notifyDataSetChanged();
    }

    public List<Song> getSongs() {
        return songs;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView albumArt;
        final TextView songTitle;
        final TextView songArtist;
        final TextView songDuration;

        ViewHolder(View itemView) {
            super(itemView);
            albumArt = itemView.findViewById(R.id.album_art);
            songTitle = itemView.findViewById(R.id.song_title);
            songArtist = itemView.findViewById(R.id.song_artist);
            songDuration = itemView.findViewById(R.id.song_duration);
        }
    }

    private static class AlbumArtLoader extends AsyncTask<Uri, Void, Bitmap> {
        private final WeakReference<ImageView> imageViewRef;
        private final ContentResolver contentResolver;

        AlbumArtLoader(ImageView imageView, ContentResolver cr) {
            this.imageViewRef = new WeakReference<>(imageView);
            this.contentResolver = cr;
        }

        @Override
        protected Bitmap doInBackground(Uri... uris) {
            try {
                InputStream is = contentResolver.openInputStream(uris[0]);
                if (is != null) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 4; // downsample for thumbnails
                    opts.inPreferredConfig = Bitmap.Config.RGB_565; // 50% RAM savings for legacy devices
                    Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
                    is.close();
                    return bmp;
                }
            } catch (Throwable ignored) {}
            return null;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            ImageView imageView = imageViewRef.get();
            if (imageView != null && bitmap != null) {
                imageView.setImageBitmap(bitmap);
            }
        }
    }
}
