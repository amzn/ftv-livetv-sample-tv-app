package com.example.android.sampletvinput;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import com.google.android.media.tv.companionlibrary.utils.TvContractUtils;

/**
 * Run when the input should initialize its content.
 */
public class InitializationReceiver extends BroadcastReceiver {
    private static final String TAG = "InitializationReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        SampleJobService.requestImmediateSync(context, TvContractUtils.INPUT_ID, new ComponentName(context, SampleJobService.class));
    }
}
