/*
 * Copyright 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.android.sampletvinput.rich;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.example.android.sampletvinput.ChannelSyncWorker;
import com.example.android.sampletvinput.SampleChannelManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BroadcastReceiver that triggers channel sync after device boot or app update.
 * Ensures channels are always present in the TIF database without requiring the user
 * to manually open the app.
 *
 * Responds to:
 * - BOOT_COMPLETED: device restarted
 * - MY_PACKAGE_REPLACED: app was updated
 *
 * Uses goAsync() + Executor to move the sync off the main thread and avoid an ANR.
 */
public class RichBootReceiver extends BroadcastReceiver {
    private static final String TAG = "RichBootReceiver";

    private static final ExecutorService SYNC_EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "Received: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            final PendingResult pendingResult = goAsync();
            SYNC_EXECUTOR.execute(() -> {
                try {
                    SampleChannelManager.syncChannels(context.getApplicationContext());
                    ChannelSyncWorker.schedule(context.getApplicationContext(),
                            ChannelSyncWorker.SYNC_INTERVAL_HOURS);
                } finally {
                    pendingResult.finish();
                }
            });
        }
    }
}
