package com.example.autosyncfree;
import android.content.Context;
import androidx.room.*;
@Database(entities = { SyncPair.class }, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    private static AppDatabase inst;
    public abstract SyncPairDao syncPairDao();
    public static synchronized AppDatabase get(Context ctx) {
        if (inst == null) {
            inst = Room.databaseBuilder(ctx.getApplicationContext(),
                AppDatabase.class, "autosync-db").build();
        }
        return inst;
    }
}
