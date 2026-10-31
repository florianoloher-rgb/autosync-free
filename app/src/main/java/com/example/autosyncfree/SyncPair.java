package com.example.autosyncfree;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "sync_pairs")
public class SyncPair {
    @PrimaryKey(autoGenerate = true) public int id;
    public String name;
    public String localTreeUri;
    public String driveFolderId;
    public boolean enabled = true;
    public long lastSyncMillis = 0;
    public String localVersionHash = "";
    public String driveVersionHash = "";
    public String driveChangesPageToken = "";

    public SyncPair() {}
}
