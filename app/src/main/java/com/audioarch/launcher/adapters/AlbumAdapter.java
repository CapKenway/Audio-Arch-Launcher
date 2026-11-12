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

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public class AlbumAdapter extends RecyclerView.Adapter<AlbumAdapter.ViewHolder> {

    public static class Album {
        private final long albumId;
        private final String name;
        private final String artist;
        private final int songCount;
        private final Uri artUri;

        public Album(long albumId, String name, String artist, int songCount, Uri artUri) {
            this.albumId = albumId;
            this.name = name;
            this.artist = artist;
            this.songCount = songCount;
            this.artUri = artUri;
        }

        public long getAlbumId() { return albumId; }
        public String getName() { return name; }
        public String getArtist() { return artist; }
        public int getSongCount() { return songCount; }
        public Uri getArtUri() { return artUri; }
    }

    public interface OnAlbumClickListener {
        void onAlbumClick(Album album);
    }

    private final Context context;
    private List<Album> albums;
    private final OnAlbumClickListener listener;

    public AlbumAdapter(Context context, List<Album> albums, OnAlbumClickListener listener) {
        this.context = context;
        this.albums = new ArrayList<>(albums);
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_album, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final Album album = albums.get(position);
        holder.albumName.setText(album.getName());
        holder.albumArtist.setText(album.getArtist());

        // Reset to placeholder
        holder.albumArt.setImageResource(R.drawable.ic_album_placeholder);

        // Load album art async
        if (album.getArtUri() != null) {
            new AlbumArtLoaderTask(holder.albumArt, context.getContentResolver())
                    .execute(album.getArtUri());
        }

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    int pos = holder.getAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) {
                        listener.onAlbumClick(albums.get(pos));
                    }
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return albums.size();
    }

    public void updateAlbums(List<Album> newAlbums) {
        this.albums = new ArrayList<>(newAlbums);
        notifyDataSetChanged();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView albumArt;
        final TextView albumName;
        final TextView albumArtist;

        ViewHolder(View itemView) {
            super(itemView);
            albumArt = itemView.findViewById(R.id.album_art);
            albumName = itemView.findViewById(R.id.album_name);
            albumArtist = itemView.findViewById(R.id.album_artist);
        }
    }

    private static class AlbumArtLoaderTask extends AsyncTask<Uri, Void, Bitmap> {
        private final WeakReference<ImageView> imageViewRef;
        private final ContentResolver contentResolver;

        AlbumArtLoaderTask(ImageView imageView, ContentResolver cr) {
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
                    opts.inPreferredConfig = Bitmap.Config.RGB_565; // save RAM
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
