package com.example.autosyncfree;

import androidx.room.*;
import java.util.List;

@Dao
public interface SyncSnapshotDao {
    @Query("SELECT * FROM sync_snapshots WHERE pairId = :pairId")
    List<SyncSnapshot> getByPair(int pairId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<SyncSnapshot> items);

    @Query("DELETE FROM sync_snapshots WHERE pairId = :pairId")
    void deleteByPair(int pairId);

    @Query("DELETE FROM sync_snapshots WHERE pairId = :pairId AND path = :path")
    void deleteOne(int pairId, String path);
}
