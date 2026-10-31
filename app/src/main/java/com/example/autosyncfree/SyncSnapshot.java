package com.example.autosyncfree;

import androidx.room.Entity;

@Entity(tableName = "sync_snapshots", primaryKeys = {"pairId", "path"})
public class SyncSnapshot {
    public int pairId;
    public String path;
    public long localMtime;
    public long driveMtime;
    public String driveFileId;
    public long size;

    public SyncSnapshot() {}
}
