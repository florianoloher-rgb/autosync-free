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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SyncJobService extends JobService {
    private static final String TAG = "SyncJob";
    private static final String EXTRA_PAIR_ID = "pairId";
    private static final int JOB_TRIGGER_OFFSET  = 1000;
    private static final int JOB_IMMEDIATE_OFFSET = 2000;
    private static final int JOB_POLL_OFFSET     = 3000;

    public static void scheduleTrigger(Context ctx, SyncPair pair) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        PersistableBundle ex = new PersistableBundle();
        ex.putInt(EXTRA_PAIR_ID, pair.id);
        try {
            JobInfo job = new JobInfo.Builder(JOB_TRIGGER_OFFSET + pair.id,
                new ComponentName(ctx, SyncJobService.class))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(
                    Uri.parse(pair.localTreeUri),
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(3000)
                .setTriggerContentMaxDelay(30000)
                .setExtras(ex)
                .build();
            js.schedule(job);
        } catch (Exception e) { Log.e(TAG, "scheduleTrigger", e); }
    }

    public static void scheduleImmediate(Context ctx, SyncPair pair) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        PersistableBundle ex = new PersistableBundle();
        ex.putInt(EXTRA_PAIR_ID, pair.id);
        try {
            JobInfo job = new JobInfo.Builder(JOB_IMMEDIATE_OFFSET + pair.id,
                new ComponentName(ctx, SyncJobService.class))
                .setExtras(ex)
                .setMinimumLatency(0)
                .setOverrideDeadline(1000)
                .build();
            js.schedule(job);
        } catch (Exception e) { Log.e(TAG, "scheduleImmediate", e); }
    }

    public static void schedulePoll(Context ctx, SyncPair pair) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        PersistableBundle ex = new PersistableBundle();
        ex.putInt(EXTRA_PAIR_ID, pair.id);
        try {
            JobInfo job = new JobInfo.Builder(JOB_POLL_OFFSET + pair.id,
                new ComponentName(ctx, SyncJobService.class))
                .setExtras(ex)
                .setMinimumLatency(30000)
                .setOverrideDeadline(60000)
                .build();
            js.schedule(job);
        } catch (Exception e) { Log.e(TAG, "schedulePoll", e); }
    }

    public static void cancelAll(Context ctx, SyncPair pair) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        js.cancel(JOB_TRIGGER_OFFSET + pair.id);
        js.cancel(JOB_IMMEDIATE_OFFSET + pair.id);
        js.cancel(JOB_POLL_OFFSET + pair.id);
    }

    public static void ensureInitialPageToken(Context ctx, SyncPair pair) {
        new Thread(() -> {
            try {
                Drive drive = buildDrive(ctx);
                if (drive == null) return;
                AppDatabase db = AppDatabase.get(ctx);
                SyncPair fresh = db.syncPairDao().getById(pair.id);
                if (fresh == null) return;
                if (fresh.driveChangesPageToken == null || fresh.driveChangesPageToken.isEmpty()) {
                    String token = drive.changes().getStartPageToken().execute().getStartPageToken();
                    fresh.driveChangesPageToken = token;
                    db.syncPairDao().update(fresh);
                    Log.i(TAG, "Initial page token for pair " + pair.id + ": " + token);
                }
                schedulePoll(ctx, fresh);
            } catch (Exception e) {
                Log.e(TAG, "ensureInitialPageToken", e);
            }
        }).start();
    }

    private static Drive buildDrive(Context ctx) {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(ctx);
        if (account == null) return null;
        GoogleAccountCredential cred = GoogleAccountCredential.usingOAuth2(
            ctx, Collections.singleton(DriveScopes.DRIVE));
        cred.setSelectedAccount(account.getAccount());
        return new Drive.Builder(new NetHttpTransport(),
            GsonFactory.getDefaultInstance(), cred)
            .setApplicationName("FloGPT AutoSync").build();
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        int pairId = params.getExtras().getInt(EXTRA_PAIR_ID, -1);
        int jobId = params.getJobId();
        if (pairId < 0) return false;

        new Thread(() -> {
            try {
                if (jobId >= JOB_POLL_OFFSET) {
                    pollDriveChanges(pairId);
                } else {
                    doSync(pairId);
                }
            } catch (Exception e) {
                Log.e(TAG, "Job " + jobId + " failed", e);
            } finally {
                jobFinished(params, false);
                try {
                    AppDatabase db = AppDatabase.get(getApplicationContext());
                    SyncPair p = db.syncPairDao().getById(pairId);
                    if (p != null && p.enabled) {
                        if (jobId >= JOB_TRIGGER_OFFSET && jobId < JOB_IMMEDIATE_OFFSET) {
                            scheduleTrigger(getApplicationContext(), p);
                        } else if (jobId >= JOB_POLL_OFFSET) {
                            schedulePoll(getApplicationContext(), p);
                        }
                    }
                } catch (Exception e) { Log.e(TAG, "reschedule", e); }
            }
        }).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return true; }

    // ─── Drive-Poll via Changes API ───
    private void pollDriveChanges(int pairId) throws Exception {
        AppDatabase db = AppDatabase.get(this);
        SyncPair pair = db.syncPairDao().getById(pairId);
        if (pair == null || !pair.enabled) return;
        Drive drive = buildDrive(this);
        if (drive == null) return;

        String token = pair.driveChangesPageToken;
        if (token == null || token.isEmpty()) {
            String start = drive.changes().getStartPageToken().execute().getStartPageToken();
            pair.driveChangesPageToken = start;
            db.syncPairDao().update(pair);
            return;
        }
        Drive.Changes.List req = drive.changes().list(token)
            .setFields("nextPageToken, newStartPageToken, changes(fileId, removed, file(id, name, trashed))")
            .setPageSize(100);
        com.google.api.services.drive.model.ChangeList changes = req.execute();

        boolean relevant = false;
        if (changes.getChanges() != null && !changes.getChanges().isEmpty()) {
            Set<String> snapIds = new HashSet<>();
            for (SyncSnapshot s : db.snapshotDao().getByPair(pairId)) {
                if (s.driveFileId != null) snapIds.add(s.driveFileId);
            }
            for (com.google.api.services.drive.model.Change c : changes.getChanges()) {
                if (c.getFileId() != null && snapIds.contains(c.getFileId())) {
                    relevant = true; break;
                }
                if (c.getFile() != null && Boolean.TRUE.equals(c.getFile().getTrashed())
                    && c.getFileId() != null && snapIds.contains(c.getFileId())) {
                    relevant = true; break;
                }
            }
        }

        if (changes.getNewStartPageToken() != null) {
            pair.driveChangesPageToken = changes.getNewStartPageToken();
        } else if (changes.getNextPageToken() != null) {
            // Für Einfachheit: poll bei nächstem Lauf weiter
        }
        db.syncPairDao().update(pair);

        if (relevant) {
            Log.i(TAG, "Drive changes detected for pair " + pairId + " → immediate sync");
            scheduleImmediate(this, pair);
        }
    }

    // ─── Haupt-Sync ───
    private void doSync(int pairId) throws Exception {
        AppDatabase db = AppDatabase.get(this);
        SyncPair pair = db.syncPairDao().getById(pairId);
        if (pair == null) return;
        Drive drive = buildDrive(this);
        if (drive == null) { Log.w(TAG, "not signed in"); return; }

        DocumentFile localRoot = DocumentFile.fromTreeUri(this, Uri.parse(pair.localTreeUri));
        if (localRoot == null || !localRoot.isDirectory()) return;

        Map<String, DocumentFile> local = new HashMap<>();
        walkLocal(localRoot, "", local);

        Map<String, com.google.api.services.drive.model.File> driveMap = new HashMap<>();
        walkDrive(drive, pair.driveFolderId, "", driveMap);

        Map<String, SyncSnapshot> snap = new HashMap<>();
        for (SyncSnapshot s : db.snapshotDao().getByPair(pairId)) snap.put(s.path, s);

        Set<String> allPaths = new HashSet<>();
        allPaths.addAll(local.keySet());
        allPaths.addAll(driveMap.keySet());
        allPaths.addAll(snap.keySet());

        Map<String, String> folderCache = new HashMap<>();
        folderCache.put("", pair.driveFolderId);

        int up = 0, down = 0, delLocal = 0, delDrive = 0;
        List<SyncSnapshot> newSnap = new ArrayList<>();

        for (String path : allPaths) {
            DocumentFile lf = local.get(path);
            com.google.api.services.drive.model.File df = driveMap.get(path);
            SyncSnapshot s = snap.get(path);

            long lMt = lf != null ? lf.lastModified() : 0;
            long dMt = (df != null && df.getModifiedTime() != null)
                ? df.getModifiedTime().getValue() : 0;
            String dId = df != null ? df.getId() : null;
            long sz = lf != null ? lf.length() : (df != null && df.getSize() != null ? df.getSize() : 0);

            boolean lExists = lf != null;
            boolean dExists = df != null;

            if (lExists && dExists) {
                boolean lChanged = s == null || lMt != s.localMtime;
                boolean dChanged = s == null || dMt != s.driveMtime;
                if (lChanged && dChanged) {
                    if (lMt >= dMt) {
                        uploadFile(drive, path, lf, df, folderCache); up++;
                        newSnap.add(snap(pairId, path, lMt, dMt, dId, sz));
                    } else {
                        downloadFile(drive, df, path, localRoot); down++;
                        DocumentFile after = findLocal(localRoot, path);
                        long newLmt = after != null ? after.lastModified() : System.currentTimeMillis();
                        long newSz = after != null ? after.length() : sz;
                        newSnap.add(snap(pairId, path, newLmt, dMt, dId, newSz));
                    }
                } else if (lChanged) {
                    uploadFile(drive, path, lf, df, folderCache); up++;
                    newSnap.add(snap(pairId, path, lMt, dMt, dId, sz));
                } else if (dChanged) {
                    downloadFile(drive, df, path, localRoot); down++;
                    DocumentFile after = findLocal(localRoot, path);
                    long newLmt = after != null ? after.lastModified() : System.currentTimeMillis();
                    long newSz = after != null ? after.length() : sz;
                    newSnap.add(snap(pairId, path, newLmt, dMt, dId, newSz));
                } else {
                    newSnap.add(snap(pairId, path, lMt, dMt, dId, sz));
                }
            } else if (lExists && !dExists) {
                if (s != null && lMt == s.localMtime) {
                    // User hat in Drive gelöscht → lokal löschen
                    lf.delete(); delLocal++;
                } else {
                    uploadFile(drive, path, lf, null, folderCache); up++;
                    String newDid = lookupDriveId(drive, path, folderCache);
                    newSnap.add(snap(pairId, path, lMt, 0, newDid, sz));
                }
            } else if (!lExists && dExists) {
                if (s != null && dMt == s.driveMtime) {
                    // User hat lokal gelöscht → in Drive löschen
                    trashDrive(drive, df.getId()); delDrive++;
                } else {
                    downloadFile(drive, df, path, localRoot); down++;
                    DocumentFile after = findLocal(localRoot, path);
                    long newLmt = after != null ? after.lastModified() : System.currentTimeMillis();
                    long newSz = after != null ? after.length() : sz;
                    newSnap.add(snap(pairId, path, newLmt, dMt, dId, newSz));
                }
            }
            // !lExists && !dExists → aus Snapshot entfernen (nicht in newSnap)
        }

        // Snapshot aktualisieren
        db.snapshotDao().deleteByPair(pairId);
        db.snapshotDao().insertAll(newSnap);

        // Hashes berechnen
        pair.localVersionHash = computeHash(local);
        pair.driveVersionHash = computeHashFromDrive(driveMap);
        pair.lastSyncMillis = System.currentTimeMillis();
        db.syncPairDao().update(pair);

        Log.i(TAG, "Sync pair " + pairId + ": " + up + " hoch, " + down + " runter, "
            + delLocal + " lokal gelöscht, " + delDrive + " drive gelöscht. "
            + "Hashes L=" + pair.localVersionHash + " D=" + pair.driveVersionHash);
    }

    private SyncSnapshot snap(int pairId, String path, long lm, long dm, String did, long sz) {
        SyncSnapshot s = new SyncSnapshot();
        s.pairId = pairId; s.path = path; s.localMtime = lm;
        s.driveMtime = dm; s.driveFileId = did; s.size = sz;
        return s;
    }

    private String lookupDriveId(Drive drive, String path, Map<String, String> cache) {
        try {
            int slash = path.lastIndexOf('/');
            String parentPath = slash > 0 ? path.substring(0, slash) : "";
            String parentId = ensureFolder(drive, parentPath, cache);
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            String q = "'" + parentId + "' in parents and name = '"
                + name.replace("'", "\\'") + "' and trashed = false";
            List<com.google.api.services.drive.model.File> found = drive.files().list()
                .setQ(q).setFields("files(id)").execute().getFiles();
            if (found != null && !found.isEmpty()) return found.get(0).getId();
        } catch (Exception ignored) {}
        return null;
    }

    private void walkLocal(DocumentFile dir, String prefix, Map<String, DocumentFile> out) {
        DocumentFile[] kids = dir.listFiles();
        if (kids == null) return;
        for (DocumentFile f : kids) {
            String n = f.getName(); if (n == null) continue;
            String p = prefix.isEmpty() ? n : prefix + "/" + n;
            if (f.isDirectory()) walkLocal(f, p, out);
            else if (f.isFile()) out.put(p, f);
        }
    }

    private void walkDrive(Drive drive, String folderId, String prefix,
                           Map<String, com.google.api.services.drive.model.File> out) throws Exception {
        String pt = null;
        do {
            Drive.Files.List req = drive.files().list()
                .setQ("'" + folderId + "' in parents and trashed = false")
                .setFields("nextPageToken, files(id, name, mimeType, modifiedTime, size)")
                .setPageSize(1000);
            if (pt != null) req.setPageToken(pt);
            com.google.api.services.drive.model.FileList res = req.execute();
            if (res.getFiles() != null) {
                for (com.google.api.services.drive.model.File f : res.getFiles()) {
                    String p = prefix.isEmpty() ? f.getName() : prefix + "/" + f.getName();
                    if ("application/vnd.google-apps.folder".equals(f.getMimeType())) {
                        walkDrive(drive, f.getId(), p, out);
                    } else out.put(p, f);
                }
            }
            pt = res.getNextPageToken();
        } while (pt != null);
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
        if (found != null && !found.isEmpty()) id = found.get(0).getId();
        else {
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
                            Map<String, String> cache) throws Exception {
        int slash = path.lastIndexOf('/');
        String parentPath = slash > 0 ? path.substring(0, slash) : "";
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        String parentId = ensureFolder(drive, parentPath, cache);

        InputStream in = getContentResolver().openInputStream(lf.getUri());
        if (in == null) throw new java.io.IOException("cannot open " + path);
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

    private void downloadFile(Drive drive, com.google.api.services.drive.model.File df,
                              String path, DocumentFile localRoot) throws Exception {
        int slash = path.lastIndexOf('/');
        String parentPath = slash > 0 ? path.substring(0, slash) : "";
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        DocumentFile parent = ensureLocalFolder(localRoot, parentPath);

        DocumentFile existing = parent.findFile(name);
        if (existing != null) existing.delete();
        String mime = df.getMimeType() != null ? df.getMimeType() : "application/octet-stream";
        DocumentFile newFile = parent.createFile(mime, name);
        if (newFile == null) throw new java.io.IOException("cannot create " + path);

        InputStream in = drive.files().get(df.getId()).executeMediaAsInputStream();
        OutputStream out = getContentResolver().openOutputStream(newFile.getUri());
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        in.close(); out.close();
    }

    private DocumentFile ensureLocalFolder(DocumentFile root, String path) {
        if (path.isEmpty()) return root;
        DocumentFile cur = root;
        for (String part : path.split("/")) {
            if (part.isEmpty()) continue;
            DocumentFile next = cur.findFile(part);
            if (next == null || !next.isDirectory()) {
                next = cur.createDirectory(part);
            }
            cur = next;
        }
        return cur;
    }

    private DocumentFile findLocal(DocumentFile root, String path) {
        if (path.isEmpty()) return root;
        DocumentFile cur = root;
        for (String part : path.split("/")) {
            if (part.isEmpty()) continue;
            cur = cur.findFile(part);
            if (cur == null) return null;
        }
        return cur;
    }

    private void trashDrive(Drive drive, String fileId) throws Exception {
        com.google.api.services.drive.model.File meta = new com.google.api.services.drive.model.File();
        meta.setTrashed(true);
        drive.files().update(fileId, meta).execute();
    }

    private String computeHash(Map<String, DocumentFile> localMap) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, DocumentFile> e : localMap.entrySet()) {
            lines.add(e.getKey() + ":" + e.getValue().length());
        }
        Collections.sort(lines);
        return sha8(String.join("\n", lines));
    }

    private String computeHashFromDrive(Map<String, com.google.api.services.drive.model.File> driveMap) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, com.google.api.services.drive.model.File> e : driveMap.entrySet()) {
            long sz = e.getValue().getSize() != null ? e.getValue().getSize() : 0;
            lines.add(e.getKey() + ":" + sz);
        }
        Collections.sort(lines);
        return sha8(String.join("\n", lines));
    }

    private String sha8(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return String.format("%02X%02X%02X%02X", h[0], h[1], h[2], h[3]);
        } catch (Exception e) { return "00000000"; }
    }
}
