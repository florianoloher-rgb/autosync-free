package com.example.autosyncfree;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private AppDatabase db;
    private PairAdapter adapter;
    private ExecutorService io = Executors.newSingleThreadExecutor();
    private Uri pendingLocalUri;
    private TextView pendingLocalStatus;
    private String pendingDriveFolderId;
    private String pendingDrivePath;
    private TextView pendingDriveStatus;
    private DriveAuthManager driveAuthManager;

    private final ActivityResultLauncher<Intent> folderPicker = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(),
        result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri uri = result.getData().getData();
                if (uri != null) {
                    getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    pendingLocalUri = uri;
                    if (pendingLocalStatus != null) pendingLocalStatus.setText(uri.toString());
                }
            }
        });

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        db = AppDatabase.get(this);
        driveAuthManager = new DriveAuthManager(this);
        RecyclerView rv = findViewById(R.id.recycler);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PairAdapter(this::deletePair);
        rv.setAdapter(adapter);
        findViewById(R.id.fab).setOnClickListener(v -> showNewPairDialog());
        reload();
    }

    private void reload() {
        io.execute(() -> {
            List<SyncPair> list = db.syncPairDao().getAll();
            runOnUiThread(() -> {
                adapter.setItems(list);
                View empty = findViewById(R.id.emptyState);
                View recycler = findViewById(R.id.recycler);
                if (list.isEmpty()) {
                    empty.setVisibility(View.VISIBLE);
                    recycler.setVisibility(View.GONE);
                } else {
                    empty.setVisibility(View.GONE);
                    recycler.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private void showNewPairDialog() {
        pendingLocalUri = null;
        pendingDriveFolderId = null;
        pendingDrivePath = null;

        android.view.View view = getLayoutInflater().inflate(R.layout.dialog_new_pair, null);
        EditText inputName = view.findViewById(R.id.inputName);
        Button btnPickLocal = view.findViewById(R.id.btnPickLocal);
        Button btnGoogle = view.findViewById(R.id.btnGoogleAuth);
        Button btnPickDrive = view.findViewById(R.id.btnPickDrive);
        pendingLocalStatus = view.findViewById(R.id.tvLocalStatus);
        pendingDriveStatus = view.findViewById(R.id.tvDriveStatus);

        btnPickLocal.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            folderPicker.launch(i);
        });

        btnGoogle.setText(driveAuthManager.getDriveService() != null
            ? "✓ Google verbunden" : "Mit Google verbinden");
        btnGoogle.setOnClickListener(v -> driveAuthManager.authorize(new DriveAuthManager.AuthListener() {
            @Override public void onAuthSuccess() {
                runOnUiThread(() -> {
                    btnGoogle.setText("✓ Google verbunden");
                    Toast.makeText(MainActivity.this, "Google verbunden", Toast.LENGTH_SHORT).show();
                });
            }
            @Override public void onAuthFailure(String msg) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "Fehler: " + msg, Toast.LENGTH_LONG).show());
            }
        }));

        btnPickDrive.setOnClickListener(v -> {
            if (driveAuthManager.getDriveService() == null) {
                Toast.makeText(this, "Erst mit Google verbinden", Toast.LENGTH_SHORT).show();
                return;
            }
            new DriveFolderBrowser(this, driveAuthManager.getDriveService(), (id, path) -> {
                pendingDriveFolderId = id;
                pendingDrivePath = path;
                runOnUiThread(() -> pendingDriveStatus.setText(
                    path + "\nID: " + id));
            }).show();
        });

        AlertDialog dlg = new AlertDialog.Builder(this)
            .setTitle("Neue Synchronisierung")
            .setView(view)
            .setPositiveButton("Speichern", null)
            .setNegativeButton("Abbrechen", null)
            .create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v -> {
                String name = inputName.getText().toString().trim();
                if (name.isEmpty() || pendingLocalUri == null || pendingDriveFolderId == null) {
                    Toast.makeText(this,
                        "Name, lokaler Ordner und Drive-Ordner nötig",
                        Toast.LENGTH_SHORT).show();
                    return;
                }
                SyncPair p = new SyncPair();
                p.name = name;
                p.localTreeUri = pendingLocalUri.toString();
                p.driveFolderId = pendingDriveFolderId;
                io.execute(() -> {
                    db.syncPairDao().insert(p);
                    runOnUiThread(() -> { reload(); dlg.dismiss(); });
                });
            }));
        dlg.show();
    }

    private void deletePair(SyncPair p) {
        io.execute(() -> {
            db.syncPairDao().delete(p);
            runOnUiThread(this::reload);
        });
    }
}
