package com.example.autosyncfree;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            new Thread(() -> {
                AppDatabase db = AppDatabase.get(ctx);
                for (SyncPair p : db.syncPairDao().getAll()) {
                    if (p.enabled) SyncJobService.scheduleTrigger(ctx, p);
                }
            }).start();
        }
    }
}
