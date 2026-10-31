package com.example.autosyncfree;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DriveFolderBrowser {
    public interface Listener { void onFolderSelected(String id, String path); }

    private static class Frame {
        String id, name;
        Frame(String id, String name) { this.id = id; this.name = name; }
    }

    private final Activity activity;
    private final Drive drive;
    private final Listener listener;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Stack<Frame> stack = new Stack<>();
    private final List<File> items = new ArrayList<>();
    private FolderAdapter adapter;
    private TextView pathView;

    public DriveFolderBrowser(Activity activity, Drive drive, Listener l) {
        this.activity = activity;
        this.drive = drive;
        this.listener = l;
        stack.push(new Frame("root", "Mein Drive"));
    }

    public void show() {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_folder_browser, null);
        RecyclerView rv = view.findViewById(R.id.folderList);
        pathView = view.findViewById(R.id.tvPath);
        rv.setLayoutManager(new LinearLayoutManager(activity));
        adapter = new FolderAdapter(items, file -> {
            stack.push(new Frame(file.getId(), file.getName()));
            loadFolders();
        });
        rv.setAdapter(adapter);

        view.findViewById(R.id.btnUp).setOnClickListener(v -> {
            if (stack.size() > 1) {
                stack.pop();
                loadFolders();
            }
        });

        new AlertDialog.Builder(activity)
            .setTitle("Drive-Ordner wählen")
            .setView(view)
            .setPositiveButton("Diesen Ordner wählen", (d, w) -> {
                Frame top = stack.peek();
                listener.onFolderSelected(top.id, buildPath());
            })
            .setNegativeButton("Abbrechen", null)
            .show();

        loadFolders();
    }

    private String buildPath() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stack.size(); i++) {
            if (i > 0) sb.append(" / ");
            sb.append(stack.get(i).name);
        }
        return sb.toString();
    }

    private void loadFolders() {
        Frame top = stack.peek();
        activity.runOnUiThread(() -> pathView.setText(buildPath()));
        io.execute(() -> {
            try {
                List<File> folders = drive.files().list()
                    .setQ("'" + top.id + "' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false")
                    .setFields("files(id, name)")
                    .setPageSize(100)
                    .execute()
                    .getFiles();
                activity.runOnUiThread(() -> {
                    items.clear();
                    if (folders != null) items.addAll(folders);
                    adapter.notifyDataSetChanged();
                });
            } catch (Exception e) {
                activity.runOnUiThread(() ->
                    Toast.makeText(activity, "Fehler: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }
}
