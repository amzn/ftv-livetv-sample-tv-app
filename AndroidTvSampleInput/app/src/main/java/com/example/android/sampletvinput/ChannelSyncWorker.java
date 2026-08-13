package com.example.android.sampletvinput;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.Worker;
import androidx.work.WorkManager;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * WorkManager Worker that periodically syncs channel and program data.
 * Ensures the EPG stays fresh even without user interaction.
 *
 * Schedule this from InitializationReceiver (on install) and RichBootReceiver (on boot).
 * Uses ExistingPeriodicWorkPolicy.KEEP — safe to call repeatedly (no-op if already scheduled).
 */
public class ChannelSyncWorker extends Worker {
    private static final String TAG = "ChannelSyncWorker";
    private static final String WORK_NAME = "live_tv_channel_sync";

    /** Default interval, in hours, between periodic channel syncs. */
    public static final int SYNC_INTERVAL_HOURS = 24;

    public ChannelSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Log.d(TAG, "Periodic channel sync starting");
            SampleChannelManager.syncChannels(getApplicationContext());
            Log.d(TAG, "Periodic channel sync complete");
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "Periodic channel sync failed", e);
            return Result.retry();
        }
    }

    /**
     * Schedules periodic channel sync. Safe to call multiple times —
     * KEEP policy ensures only one periodic job exists.
     *
     * @param context Application context
     * @param intervalHours Sync interval in hours
     */
    public static void schedule(Context context, int intervalHours) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                ChannelSyncWorker.class, intervalHours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
        );

        Log.d(TAG, "Scheduled periodic sync every " + intervalHours + " hours");
    }
}
