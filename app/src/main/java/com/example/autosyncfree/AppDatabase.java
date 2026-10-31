package com.example.autosyncfree;

import android.content.Context;
import androidx.room.*;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = { SyncPair.class, SyncSnapshot.class }, version = 2, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    private static AppDatabase inst;
    public abstract SyncPairDao syncPairDao();
    public abstract SyncSnapshotDao snapshotDao();

    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE sync_pairs ADD COLUMN localVersionHash TEXT DEFAULT ''");
            db.execSQL("ALTER TABLE sync_pairs ADD COLUMN driveVersionHash TEXT DEFAULT ''");
            db.execSQL("ALTER TABLE sync_pairs ADD COLUMN driveChangesPageToken TEXT DEFAULT ''");
            db.execSQL("CREATE TABLE IF NOT EXISTS sync_snapshots (" +
                "pairId INTEGER NOT NULL, " +
                "path TEXT NOT NULL, " +
                "localMtime INTEGER NOT NULL, " +
                "driveMtime INTEGER NOT NULL, " +
                "driveFileId TEXT, " +
                "size INTEGER NOT NULL, " +
                "PRIMARY KEY(pairId, path))");
        }
    };

    public static synchronized AppDatabase get(Context ctx) {
        if (inst == null) {
            inst = Room.databaseBuilder(ctx.getApplicationContext(),
                AppDatabase.class, "autosync-db")
                .addMigrations(MIGRATION_1_2)
                .build();
        }
        return inst;
    }
}
