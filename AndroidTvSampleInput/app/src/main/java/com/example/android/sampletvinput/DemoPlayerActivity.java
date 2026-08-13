package com.example.android.sampletvinput;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.ui.StyledPlayerView;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * This activity handles the deeplink URI to playback content within this app.
 * You should implement your own in-app player here.
 *
 * This activity is just used to demo how your app can receive the TIF ID and other
 * information from the intent.
 */
public class DemoPlayerActivity extends Activity {

    private static final String TAG = DemoPlayerActivity.class.getSimpleName();
    private static final String KEY_VIDEO_URL = "videoUrl";

    private ExoPlayer mPlayer;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.demo_player_activity);

        Toast.makeText(this, "Launch customized player activity", Toast.LENGTH_SHORT).show();

        StyledPlayerView playerView = findViewById(R.id.player_view);
        mPlayer = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(mPlayer);

        initializePlayerActivity(getIntent());
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        initializePlayerActivity(intent);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }
    }

    private void initializePlayerActivity(final Intent intent) {
        final int originalNetworkId = intent.getIntExtra(
                SampleChannelManager.CHANNEL_DEEP_LINK_INTENT_PRIM_KEY, -1);
        final String inputId = intent.getStringExtra(
                SampleChannelManager.CHANNEL_DEEP_LINK_INTENT_SEC_KEY);

        // Logged for demo visibility only.
        Log.d(TAG, "Deep link: originalNetworkId=" + originalNetworkId + " inputId=" + inputId);

        if (originalNetworkId == -1 || inputId == null) {
            Log.e(TAG, "Invalid deep link intent - missing channel info");
            finish();
            return;
        }

        final Uri channelUri = findChannelByNetworkId(originalNetworkId);
        if (channelUri == null) {
            Log.e(TAG, "Channel not found for originalNetworkId=" + originalNetworkId);
            finish();
            return;
        }
        playChannel(channelUri);
    }

    private Uri findChannelByNetworkId(final int originalNetworkId) {
        final SharedPreferences prefs = getSharedPreferences(
                SampleChannelManager.PREFS_CHANNEL_MAP, MODE_PRIVATE);
        final long channelId = prefs.getLong(String.valueOf(originalNetworkId), -1);
        if (channelId == -1) {
            return null;
        }
        return TvContract.buildChannelUri(channelId);
    }

    private void playChannel(final Uri channelUri) {
        final long channelId = Long.parseLong(channelUri.getLastPathSegment());
        final long now = System.currentTimeMillis();

        // A 1ms window at "now" returns exactly the program airing at this instant
        // (its start_time <= now < end_time).
        final Uri programUri = TvContract.buildProgramsUriForChannel(channelId, now, now + 1);
        final ContentResolver resolver = getContentResolver();

        try (Cursor cursor = resolver.query(programUri,
                new String[]{TvContract.Programs.COLUMN_INTERNAL_PROVIDER_DATA},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                final byte[] data = cursor.getBlob(0);
                if (data != null) {
                    final JSONObject programData = new JSONObject(new String(data));
                    final String videoUrl = programData.optString(KEY_VIDEO_URL, null);

                    if (videoUrl != null) {
                        final Uri uri = Uri.parse(videoUrl);

                        MediaItem mediaItem = MediaItem.fromUri(uri);
                        mPlayer.setMediaItem(mediaItem);
                        mPlayer.prepare();
                        mPlayer.play();
                        return;
                    }
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing program data", e);
        }

        Log.w(TAG, "No program or video URL found for channel");
    }
}
