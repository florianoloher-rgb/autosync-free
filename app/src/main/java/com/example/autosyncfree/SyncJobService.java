package com.example.autosyncfree;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.PersistableBundle;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SyncJobService extends JobService {
    private static final String TAG = "SyncJob";
    private static final String EXTRA_PAIR_ID = "pairId";

    public static void scheduleTrigger(Context ctx, SyncPair pair) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        PersistableBundle extras = new PersistableBundle();
        extras.putInt(EXTRA_PAIR_ID, pair.id);
        try {
            JobInfo job = new JobInfo.Builder(1000 + pair.id, new ComponentName(ctx, SyncJobService.class))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(
                    Uri.parse(pair.localTreeUri),
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(3000)
                .setTriggerContentMaxDelay(60000)
                .setExtras(extras)
                .build();
            js.schedule(job);
            Log.i(TAG, "Trigger scheduled for pair " + pair.id);
        } catch (Exception e) {
            Log.e(TAG, "scheduleTrigger failed", e);
        }
    }

    public static void scheduleImmediate(Context ctx, SyncPair pair) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        PersistableBundle extras = new PersistableBundle();
        extras.putInt(EXTRA_PAIR_ID, pair.id);
        try {
            JobInfo job = new JobInfo.Builder(2000 + pair.id, new ComponentName(ctx, SyncJobService.class))
                .setExtras(extras)
                .setMinimumLatency(0)
                .setOverrideDeadline(1000)
                .build();
            js.schedule(job);
            Log.i(TAG, "Immediate scheduled for pair " + pair.id);
        } catch (Exception e) {
            Log.e(TAG, "scheduleImmediate failed", e);
        }
    }

    public static void cancelAll(Context ctx, SyncPair pair) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        js.cancel(1000 + pair.id);
        js.cancel(2000 + pair.id);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        int pairId = params.getExtras().getInt(EXTRA_PAIR_ID, -1);
        final int jobId = params.getJobId();
        if (pairId < 0) return false;

        new Thread(() -> {
            try {
                doSync(pairId);
            } catch (Exception e) {
                Log.e(TAG, "Sync failed for pair " + pairId, e);
            } finally {
                jobFinished(params, false);
                if (jobId < 2000) {
                    try {
                        AppDatabase db = AppDatabase.get(getApplicationContext());
                        SyncPair p = db.syncPairDao().getById(pairId);
                        if (p != null && p.enabled) {
                            scheduleTrigger(getApplicationContext(), p);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Re-schedule failed", e);
                    }
                }
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    private void doSync(int pairId) throws Exception {
        AppDatabase db = AppDatabase.get(this);
        SyncPair pair = db.syncPairDao().getById(pairId);
        if (pair == null) { Log.w(TAG, "Pair " + pairId + " missing"); return; }

        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        if (account == null) { Log.w(TAG, "Not signed in - skip"); return; }

        GoogleAccountCredential cred = GoogleAccountCredential.usingOAuth2(
            this, Collections.singleton(DriveScopes.DRIVE));
        cred.setSelectedAccount(account.getAccount());
        Drive drive = new Drive.Builder(
            new NetHttpTransport(), GsonFactory.getDefaultInstance(), cred)
            .setApplicationName("FloGPT AutoSync").build();

        DocumentFile localRoot = DocumentFile.fromTreeUri(this, Uri.parse(pair.localTreeUri));
        if (localRoot == null || !localRoot.isDirectory()) {
            Log.e(TAG, "Local folder unreadable"); return;
        }

        Map<String, DocumentFile> localFiles = new HashMap<>();
        walkLocal(localRoot, "", localFiles);
        Log.i(TAG, "Local files: " + localFiles.size());

        Map<String, com.google.api.services.drive.model.File> driveFiles = new HashMap<>();
        walkDrive(drive, pair.driveFolderId, "", driveFiles);
        Log.i(TAG, "Drive files: " + driveFiles.size());

        Map<String, String> folderCache = new HashMap<>();
        folderCache.put("", pair.driveFolderId);

        int uploaded = 0, updated = 0;
        for (Map.Entry<String, DocumentFile> e : localFiles.entrySet()) {
            String path = e.getKey();
            DocumentFile lf = e.getValue();
            com.google.api.services.drive.model.File existing = driveFiles.get(path);

            long localTime = lf.lastModified();
            long driveTime = (existing != null && existing.getModifiedTime() != null)
                ? existing.getModifiedTime().getValue() : 0;

            boolean needsUpload = (existing == null) || (localTime > driveTime + 1000);

            if (needsUpload) {
                try {
                    uploadFile(drive, path, lf, existing, folderCache);
                    if (existing == null) uploaded++; else updated++;
                } catch (Exception ex) {
                    Log.e(TAG, "Upload failed: " + path, ex);
                }
            }
        }

        pair.lastSyncMillis = System.currentTimeMillis();
        db.syncPairDao().update(pair);
        Log.i(TAG, "Sync done pair " + pairId + ": " + uploaded + " neu, " + updated + " aktualisiert");
    }

    private void walkLocal(DocumentFile dir, String prefix, Map<String, DocumentFile> out) {
        DocumentFile[] kids = dir.listFiles();
        if (kids == null) return;
        for (DocumentFile f : kids) {
            String name = f.getName();
            if (name == null) continue;
            String path = prefix.isEmpty() ? name : prefix + "/" + name;
            if (f.isDirectory()) walkLocal(f, path, out);
            else if (f.isFile()) out.put(path, f);
        }
    }

    private void walkDrive(Drive drive, String folderId, String prefix,
                           Map<String, com.google.api.services.drive.model.File> out) throws Exception {
        String pageToken = null;
        do {
            Drive.Files.List req = drive.files().list()
                .setQ("'" + folderId + "' in parents and trashed = false")
                .setFields("nextPageToken, files(id, name, mimeType, modifiedTime)")
                .setPageSize(1000);
            if (pageToken != null) req.setPageToken(pageToken);
            com.google.api.services.drive.model.FileList res = req.execute();
            if (res.getFiles() != null) {
                for (com.google.api.services.drive.model.File f : res.getFiles()) {
                    String path = prefix.isEmpty() ? f.getName() : prefix + "/" + f.getName();
                    if ("application/vnd.google-apps.folder".equals(f.getMimeType())) {
                        walkDrive(drive, f.getId(), path, out);
                    } else {
                        out.put(path, f);
                    }
                }
            }
            pageToken = res.getNextPageToken();
        } while (pageToken != null);
    }

    private String ensureFolder(Drive drive, String path, Map<String, String> cache) throws Exception {
        if (cache.containsKey(path)) return cache.get(path);
        String parentPath = "";
        String name = path;
        int slash = path.lastIndexOf('/');
        if (slash >= 0) { parentPath = path.substring(0, slash); name = path.substring(slash + 1); }
        String parentId = ensureFolder(drive, parentPath, cache);

        String safe = name.replace("\\", "\\\\").replace("'", "\\'");
        String q = "'" + parentId + "' in parents and name = '" + safe
            + "' and mimeType = 'application/vnd.google-apps.folder' and trashed = false";
        List<com.google.api.services.drive.model.File> found = drive.files().list()
            .setQ(q).setFields("files(id)").execute().getFiles();
        String id;
        if (found != null && !found.isEmpty()) {
            id = found.get(0).getId();
        } else {
            com.google.api.services.drive.model.File meta = new com.google.api.services.drive.model.File();
            meta.setName(name);
            meta.setMimeType("application/vnd.google-apps.folder");
            meta.setParents(Collections.singletonList(parentId));
            id = drive.files().create(meta).setFields("id").execute().getId();
        }
        cache.put(path, id);
        return id;
    }

    private void uploadFile(Drive drive, String path, DocumentFile lf,
                            com.google.api.services.drive.model.File existing,
                            Map<String, String> folderCache) throws Exception {
        int slash = path.lastIndexOf('/');
        String parentPath = slash > 0 ? path.substring(0, slash) : "";
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        String parentId = ensureFolder(drive, parentPath, folderCache);

        InputStream in = getContentResolver().openInputStream(lf.getUri());
        if (in == null) throw new java.io.IOException("Cannot open " + path);
        String mime = getContentResolver().getType(lf.getUri());
        if (mime == null) mime = "application/octet-stream";
        InputStreamContent content = new InputStreamContent(mime, in);
        content.setLength(lf.length());

        if (existing == null) {
            com.google.api.services.drive.model.File meta = new com.google.api.services.drive.model.File();
            meta.setName(name);
            meta.setParents(Collections.singletonList(parentId));
            drive.files().create(meta, content).setFields("id").execute();
        } else {
            com.google.api.services.drive.model.File meta = new com.google.api.services.drive.model.File();
            meta.setName(name);
            drive.files().update(existing.getId(), meta, content).execute();
        }
        in.close();
    }
}
