package com.example.autosyncfree;

import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.*;

public class PairAdapter extends RecyclerView.Adapter<PairAdapter.VH> {
    public interface Listener {
        void onDelete(SyncPair p);
        void onSyncNow(SyncPair p);
    }
    private final List<SyncPair> items = new ArrayList<>();
    private final Listener listener;

    public PairAdapter(Listener l) { this.listener = l; }

    public void setItems(List<SyncPair> list) {
        items.clear(); items.addAll(list); notifyDataSetChanged();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup p, int v) {
        View view = LayoutInflater.from(p.getContext())
            .inflate(R.layout.item_pair, p, false);
        return new VH(view);
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        SyncPair p = items.get(pos);
        h.name.setText(p.name);
        h.local.setText(p.localTreeUri);
        h.drive.setText(p.driveFolderId);
        h.btnDelete.setOnClickListener(v -> listener.onDelete(p));
        h.btnSync.setOnClickListener(v -> listener.onSyncNow(p));
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView name, local, drive;
        Button btnDelete, btnSync;
        VH(View v) {
            super(v);
            name = v.findViewById(R.id.tvName);
            local = v.findViewById(R.id.tvLocal);
            drive = v.findViewById(R.id.tvDrive);
            btnDelete = v.findViewById(R.id.btnDelete);
            btnSync = v.findViewById(R.id.btnSync);
        }
    }
}
