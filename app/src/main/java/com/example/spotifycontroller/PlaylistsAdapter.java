package com.example.spotifycontroller;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class PlaylistsAdapter extends RecyclerView.Adapter<PlaylistsAdapter.ViewHolder> {

    public class ViewHolder extends RecyclerView.ViewHolder {
        public TextView nameTextView;
        public TextView descriptionTextView;
        public TextView infoTextView;
        public ImageView imageView;

        public ViewHolder(View itemView) {
            super(itemView);
            nameTextView = (TextView) itemView.findViewById(R.id.textName);
            descriptionTextView = (TextView) itemView.findViewById(R.id.textDescription);
            infoTextView = (TextView) itemView.findViewById(R.id.textInfo);
            imageView = (ImageView) itemView.findViewById(R.id.imageView);
        }
    }

    private List<Playlist> playlists;

    public PlaylistsAdapter(List<Playlist> playlists) {
        this.playlists = playlists;
    }

    @Override
    public PlaylistsAdapter.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        Context context = parent.getContext();
        LayoutInflater inflater = LayoutInflater.from(context);

        // Inflate the custom layout
        View playlistView = inflater.inflate(R.layout.item_playlist, parent, false);

        // Return a new holder instance
        ViewHolder viewHolder = new ViewHolder(playlistView);
        return viewHolder;
    }

    @Override
    public void onBindViewHolder(PlaylistsAdapter.ViewHolder holder, int position) {
        Playlist playlist = playlists.get(position);

        // set view items to contain playlist data
        TextView nameTextView = holder.nameTextView;
        nameTextView.setText(playlist.getName());

        TextView descriptionTextView = holder.descriptionTextView;
        descriptionTextView.setText(playlist.getDescription());

        TextView infoTextView = holder.infoTextView;
        infoTextView.setText(playlist.getNumberOfTracks()+" tracks");

        if (playlist.getImage() != null) {
            ImageView imageView = holder.imageView;
            imageView.setImageBitmap(playlist.getImage());
        }
    }

    @Override
    public int getItemCount() {
        return playlists.size();
    }
}