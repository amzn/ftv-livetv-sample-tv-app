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

import android.content.ComponentName;
import android.content.Context;
import android.media.tv.TvContentRating;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;

import com.example.android.sampletvinput.R;
import com.example.android.sampletvinput.SampleJobService;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import com.google.android.media.tv.companionlibrary.BaseTvInputService;
import com.google.android.media.tv.companionlibrary.EpgSyncJobService;
import com.google.android.media.tv.companionlibrary.TvPlayer;
import com.google.android.media.tv.companionlibrary.model.Channel;
import com.google.android.media.tv.companionlibrary.model.InternalProviderData;
import com.google.android.media.tv.companionlibrary.model.Program;

/**
 * TvInputService which provides a full implementation of EPG, subtitles, multi-audio, parental
 * controls, and overlay view.
 */
public class RichTvInputService extends BaseTvInputService implements Player.Listener{
    private static final String TAG = "RichTvInputService";
    private static final boolean DEBUG = false;
    private static final long EPG_SYNC_DELAYED_PERIOD_MS = 1000 * 2; // 2 Seconds

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public final Session onCreateSession(String inputId) {
        RichTvInputSessionImpl session = new RichTvInputSessionImpl(this, inputId);
        session.setOverlayViewEnabled(true);
        return super.sessionCreated(session);
    }

    /**
     * The session implementation handles playback in Fire TV UI. This supports both preview playback
     * as well as full playback in the native Fire TV player. 
     */
    class RichTvInputSessionImpl extends BaseTvInputService.Session {
        private static final float CAPTION_LINE_HEIGHT_RATIO = 0.0533f;
        private static final String GRACENOTE_ID = "gracenote_ontv";

        // Example MPEG-DASH test content from Telecom Paris. Stream is licensed Creative Commons.
        // See http://download.tsi.telecom-paristech.fr/gpac/dataset/dash/uhd/ for more information.
        private static final String GRACENOTE_TEST_STREAM_URL = "http://download.tsi.telecom-paristech.fr/gpac/DASH_CONFORMANCE/TelecomParisTech/mp4-live/mp4-live-mpd-AV-NBS.mpd";

        private StyledPlayerView mPlayerView;
        private ExoPlayer mPlayer;
        private PlayerEventListener mPlayerListener;
        private String mInputId;
        private Context mContext;

        RichTvInputSessionImpl(Context context, String inputId) {
            super(context, inputId);
            mContext = context;
            mInputId = inputId;
            mPlayerListener = new PlayerEventListener();
        }

        /**
         * PLAYBACK-FTVUI 7: onPlayProgram()
         * This method checks for a valid program and then creates the DemoPlayer object and flags
         * it to play when ready
         */
        @Override
        public boolean onPlayProgram(Program program, long startPosMs, Channel channel) {
            // Play the specified program (if it exists).
            boolean programExists = (null != program);
            if (programExists) {
                // The feed for the program is stored in its internalProviderData column.
                createPlayer(Uri.parse(program.getInternalProviderData().getVideoUrl()));
                if (startPosMs > 0) {
                    mPlayer.seekTo(startPosMs);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    notifyTimeShiftStatusChanged(TvInputManager.TIME_SHIFT_STATUS_AVAILABLE);
                }
                mPlayer.setPlayWhenReady(true);
                return true;
            }

            // Determine if the channel being played is populated with program metadata from an external
            // catalog. The `program` argument of this method only refers to TIF-based program metadata,
            // so if external catalog data is being used, the missing program is expected and we should
            // display content based on the channel alone.
            //
            // NOTE: "External catalog" can refer to either Gracenote or to Amazon's native catalog.
            //       See https://developer.amazon.com/docs/catalog/getting-started-catalog-ingestion.html.
            boolean canPlayFromExternalCatalogData = false;
            boolean channelExists = (null != channel);
            if (channelExists) {
                InternalProviderData channelInternalProviderData = channel.getInternalProviderData();
                boolean channelHasInternalProviderData = (null != channelInternalProviderData);
                if (channelHasInternalProviderData) {
                    String channelExternalIdType = channelInternalProviderData.getExternalIdType();
                    boolean channelHasExternalIdType = (null != channelExternalIdType);
                    if (channelHasExternalIdType) {
                        canPlayFromExternalCatalogData = channelExternalIdType.equals(GRACENOTE_ID);
                    }
                }
            }

            // Play content for the specified channel (if possible).
            if (canPlayFromExternalCatalogData) {
                // A simple test stream is played for all Gracenote channels. A production-ready implementation
                // would obviously be more complex and would need to select the appropriate stream for the channel.
                createPlayer(
                        Uri.parse(GRACENOTE_TEST_STREAM_URL));
                mPlayer.setPlayWhenReady(true);
                return true;
            }

            // A program was not specified and either a channel was not specified or the specified channel
            // does not get metadata from an external catalog, so we have nothing to display. We can request
            // an EPG sync in case that resolves the metadata issue within the TIF database.
            requestEpgSync(getCurrentChannelUri());
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING);
            return false;
        }

        public TvPlayer getTvPlayer() {
            return (TvPlayer) mPlayer;
        }

        /**
         * onTune is the initial call made by Fire TV into the app when the user triggers
         * playback in the Fire TV UI. Follow these comments tagged with "PLAYBACK-FTVUI" to trace the full
         * flow of playback as implemented in this sample app.
         * <p>
         * PLAYBACK-FTVUI 1: onTune
         */
        @Override
        public boolean onTune(Uri channelUri) {
            if (DEBUG) {
                Log.d(TAG, "Tune to " + channelUri.toString());
            }
            // This notification tells Fire TV that we are currently tuning to the requested channel
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING);
            // When tuning we always release any prior playback state
            releasePlayer();
            return super.onTune(channelUri);
        }

        @Override
        public void onSetCaptionEnabled(boolean enabled) {
            // stub
        }

        @Override
        public void onRelease() {
            super.onRelease();
            releasePlayer();
        }

        @Override
        public void onBlockContent(TvContentRating rating) {
            super.onBlockContent(rating);
            releasePlayer();
        }

        public void requestEpgSync(final Uri channelUri) {
            EpgSyncJobService.requestImmediateSync(RichTvInputService.this, mInputId,
                    new ComponentName(RichTvInputService.this, SampleJobService.class));
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    onTune(channelUri);
                }
            }, EPG_SYNC_DELAYED_PERIOD_MS);
        }

        /**
         * PLAYBACK-FTVUI 8: createPlayer()
         * Creates the player with the specific video url for the program.
         */
        private void createPlayer(Uri videoUrl) {
            releasePlayer();
            mPlayer = new ExoPlayer.Builder(mContext)
                    .build();
            LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
            mPlayerView = (StyledPlayerView) inflater.inflate(R.layout.demo_player_activity, null);
            mPlayerView.setPlayer(mPlayer);
            mPlayer.addListener(mPlayerListener);
            mPlayer.addMediaItem(MediaItem.fromUri(videoUrl));
            mPlayer.setPlayWhenReady(true);
            mPlayer.prepare();
        }

        private void releasePlayer() {
            if (mPlayer != null) {
                mPlayer.removeListener(mPlayerListener);
                mPlayer.setVideoSurface(null);
                mPlayer.stop();
                mPlayer.release();
                mPlayer = null;
            }
        }

        private class PlayerEventListener implements Player.Listener {
            /**
             * PLAYBACK-FTVUI 9: onStateChanged()
             * Triggered by the DemoPlayer once playback begins. Invokes critical notifications back to Fire TV UI to complete the flow.
             */
            @Override
            public void onPlayerStateChanged(boolean playWhenReady, @Player.State int playbackState) {
                if (mPlayer == null) {
                    return;
                }

                if (playWhenReady && playbackState == ExoPlayer.STATE_READY) {
                    // These notifications alert the Fire TV UI that the player is ready to playback
                    // These two calls must fire only once the player is fully ready to play
                    notifyVideoAvailable();
                    notifyContentAllowed();
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        Math.abs(mPlayer.getPlaybackParameters().speed - 1.0f) < 0.1 &&
                        playWhenReady && playbackState == ExoPlayer.STATE_BUFFERING) {
                    notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING);
                }
            }
        }
    }

}
