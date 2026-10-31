package com.example.autosyncfree;
import androidx.room.*;
import java.util.List;
@Dao
public interface SyncPairDao {
    @Query("SELECT * FROM sync_pairs ORDER BY id DESC")
    List<SyncPair> getAll();
    @Query("SELECT * FROM sync_pairs WHERE id = :id")
    SyncPair getById(int id);
    @Insert long insert(SyncPair pair);
    @Update void update(SyncPair pair);
    @Delete void delete(SyncPair pair);
}
