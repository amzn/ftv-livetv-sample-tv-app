package com.example.android.sampletvinput;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.google.android.media.tv.companionlibrary.utils.TvContractUtils;

/**
 * Run when the input should initialize its content.
 */
public class InitializationReceiver extends BroadcastReceiver {
    private static final String TAG = InitializationReceiver.class.getSimpleName();

    /**
     * Receives intent with action INITIALIZE_PROGRAMS which is broadcast after installation.
     * This can be leveraged to sync channel data to the device (TIF database) after
     * installation without opening the application.
     *
     * NOTE: For more information on what channel data to push to the device see:
     * https://developer.amazon.com/docs/fire-tv/tv-input-framework-on-fire-tv.html#live-channel-entitlements
     *
     * Use the below command to see the relevant logs:
     *
     * adb logcat TvInputChangeReceiver:D TvContractUtils:D InitializationReceiver:D *:S
     *
     * Since INITIALIZE_PROGRAMS is only sent when the app is installed/sideloaded,
     * you can quickly retest the receiver/initialization logic using the command below:
     *
     * adb shell am broadcast -a android.media.tv.action.INITIALIZE_PROGRAMS -n \
     * com.example.android.sampletvinput/.InitializationReceiver
     * */
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Received intent with action " + intent.getAction());
        SampleJobService.requestImmediateSync(context, TvContractUtils.INPUT_ID, new ComponentName(context, SampleJobService.class));
    }
}
