package com.example.autosyncfree;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.api.services.drive.model.File;

import java.util.List;

public class FolderAdapter extends RecyclerView.Adapter<FolderAdapter.VH> {
    public interface OnClick { void onClick(File f); }
    private final List<File> items;
    private final OnClick onClick;

    public FolderAdapter(List<File> items, OnClick c) {
        this.items = items;
        this.onClick = c;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup p, int v) {
        View view = LayoutInflater.from(p.getContext())
            .inflate(R.layout.item_folder, p, false);
        return new VH(view);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        File f = items.get(pos);
        h.name.setText(f.getName());
        h.itemView.setOnClickListener(v -> onClick.onClick(f));
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView name;
        VH(View v) { super(v); name = v.findViewById(R.id.tvFolderName); }
    }
}
