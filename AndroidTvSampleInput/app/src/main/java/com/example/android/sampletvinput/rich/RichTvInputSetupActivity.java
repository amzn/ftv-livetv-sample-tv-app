/*
 * Copyright 2015 The Android Open Source Project
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

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.example.android.sampletvinput.R;
import com.example.android.sampletvinput.SampleChannelManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SetupActivity for the sample TV input service.
 * Launched from Settings > Live TV > Sync Sources > [app name].
 *
 * Displays three states:
 * - In progress: "Updating Channel Info" with spinner
 * - Success: "Channel Sync Complete" with auto-exit after 3 seconds
 * - Failure: "Channel Update Failed" with auto-exit after 3 seconds
 */
public class RichTvInputSetupActivity extends Activity {
    private static final String TAG = "RichTvInputSetupActivity";
    private static final int AUTO_EXIT_DELAY_MS = 3000;

    private TextView mTitleText;
    private TextView mMessageText;
    private ProgressBar mProgressBar;
    private final Handler mExitHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mSyncExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.rich_setup);

        mTitleText = findViewById(R.id.setup_title_text);
        mMessageText = findViewById(R.id.setup_message_text);
        mProgressBar = findViewById(R.id.setup_progress_bar);

        startSync();
    }

    private void startSync() {
        mTitleText.setText("Updating Channel Info");
        mMessageText.setText("This may take a few minutes, please do not leave this screen");
        mProgressBar.setVisibility(View.VISIBLE);

        mSyncExecutor.execute(() -> {
            try {
                boolean success = SampleChannelManager.syncChannels(getApplicationContext());
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (success) showSuccess(); else showFailure();
                });
            } catch (Exception e) {
                Log.e(TAG, "Channel sync failed", e);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    showFailure();
                });
            }
        });
    }

    private void showSuccess() {
        mProgressBar.setVisibility(View.GONE);
        mTitleText.setText("Channel Sync Complete");
        mMessageText.setText("This screen will exit shortly");
        exitAfterDelay(Activity.RESULT_OK);
    }

    private void showFailure() {
        mProgressBar.setVisibility(View.GONE);
        mTitleText.setText("Channel Update Failed");
        mMessageText.setText("Please try again later");
        exitAfterDelay(Activity.RESULT_CANCELED);
    }

    private void exitAfterDelay(int resultCode) {
        mExitHandler.postDelayed(() -> {
            setResult(resultCode);
            finish();
        }, AUTO_EXIT_DELAY_MS);
    }

    @Override
    protected void onDestroy() {
        mExitHandler.removeCallbacksAndMessages(null);
        mSyncExecutor.shutdownNow();
        super.onDestroy();
    }
}
