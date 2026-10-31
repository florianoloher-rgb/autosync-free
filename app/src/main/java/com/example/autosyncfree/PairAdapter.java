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

        String lh = p.localVersionHash == null || p.localVersionHash.isEmpty() ? "—" : p.localVersionHash;
        String dh = p.driveVersionHash == null || p.driveVersionHash.isEmpty() ? "—" : p.driveVersionHash;
        h.hashes.setText("Lokal: " + lh + "  ·  Drive: " + dh);

        boolean synced = !lh.equals("—") && lh.equals(dh);
        if (synced) {
            h.status.setText("● Synchronisiert");
            h.status.setTextColor(0xFF10B981);
            h.hashes.setTextColor(0xFF10B981);
        } else if (lh.equals("—")) {
            h.status.setText("● Noch nicht gesynct");
            h.status.setTextColor(0xFF64748B);
            h.hashes.setTextColor(0xFF64748B);
        } else {
            h.status.setText("● Nicht synchron");
            h.status.setTextColor(0xFFF59E0B);
            h.hashes.setTextColor(0xFFF59E0B);
        }

        h.btnDelete.setOnClickListener(v -> listener.onDelete(p));
        h.btnSync.setOnClickListener(v -> listener.onSyncNow(p));
    }

    @Override public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView name, local, drive, status, hashes;
        Button btnDelete, btnSync;
        VH(View v) {
            super(v);
            name = v.findViewById(R.id.tvName);
            local = v.findViewById(R.id.tvLocal);
            drive = v.findViewById(R.id.tvDrive);
            status = v.findViewById(R.id.tvStatus);
            hashes = v.findViewById(R.id.tvHashes);
            btnDelete = v.findViewById(R.id.btnDelete);
            btnSync = v.findViewById(R.id.btnSync);
        }
    }
}
