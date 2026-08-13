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

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContentRating;
import android.media.tv.TvContract;
import android.media.tv.TvInputManager;
import android.media.tv.TvInputService;
import android.media.tv.TvTrackInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Tracks;

import com.example.android.sampletvinput.SampleChannelManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TvInputService providing playback for the Fire TV Live TV UI.
 *
 * When a user tunes to a channel, Fire TV calls onCreateSession() to get a Session
 * that handles playback via the provided Surface.
 *
 * Supports:
 * - Preview playback (mini player when focusing on a channel tile)
 * - Full-screen playback via the native Fire TV player
 * - Parental controls (PCON)
 * - Gracenote and Amazon catalog channels with external metadata
 *
 * See: https://developer.amazon.com/docs/fire-tv/playback-in-fire-tv-ui.html
 */
public class RichTvInputService extends TvInputService {
    private static final String TAG = "RichTvInputService";

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public Session onCreateSession(String inputId) {
        Log.d(TAG, "onCreateSession: " + inputId);
        return new RichTvInputSessionImpl(this, inputId);
    }

    /**
     * Session implementation for Fire TV Live TV playback.
     *
     * Fire TV calls onSetSurface() to provide a Surface for rendering, then onTune() to
     * request playback of a specific channel. The session must:
     * 1. Look up the channel and its current program
     * 2. Resolve the video URL from the program's INTERNAL_PROVIDER_DATA
     * 3. Start playback on the provided Surface
     * 4. Notify Fire TV when video is available
     * 5. Handle parental controls (PCON)
     *
     */
    private class RichTvInputSessionImpl extends TvInputService.Session {

        // Tears of Steel — (CC) Blender Foundation | mango.blender.org
        // Used for every external-metadata (Gracenote/Amazon catalog) channel regardless
        // of program for this demo app.
        private static final String TEST_STREAM_URL =
                "https://storage.googleapis.com/wvmedia/clear/h264/tears/tears.mpd";

        // JSON keys for INTERNAL_PROVIDER_DATA
        private static final String KEY_EXTERNAL_ID_TYPE = "externalIdType";
        private static final String KEY_VIDEO_URL = "videoUrl";

        private final Context mContext;
        private final String mInputId;
        private final TvInputManager mTvInputManager;
        private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
        private final Handler mMainHandler = new Handler(Looper.getMainLooper());
        private Surface mSurface;
        private ExoPlayer mPlayer;
        private Uri mCurrentChannelUri;
        private TvContentRating mBlockedRating;
        private volatile boolean mReleased;
        // Re-checks the channel when its program ends, so playback auto-advances.
        // Tracked so a new tune or release can cancel a stale pending check.
        private Runnable mAdvanceToNextProgramRunnable;
        // Reapplied to each new player, since ExoPlayer always starts at full volume —
        // otherwise a volume set before a re-tune would be lost.
        private float mVolume = 1f;

        RichTvInputSessionImpl(Context context, String inputId) {
            super(context);
            mContext = context;
            mInputId = inputId;
            mTvInputManager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
        }

        /**
         * Called by Fire TV to provide the Surface for video rendering.
         * Store it and pass to the player when playback starts.
         *
         */
        @Override
        public boolean onSetSurface(@Nullable Surface surface) {
            mSurface = surface;
            if (mPlayer != null) {
                mPlayer.setVideoSurface(surface);
            }
            return true;
        }

        /**
         * Called by Fire TV when the user tunes to a channel.
         * This is the main entry point for playback.
         *
         * Flow:
         * 1. Notify Fire TV we are tuning (shows loading state)
         * 2. Check parental controls — if enabled, block and wait for PIN
         * 3. If not blocked, resolve video URL on a background thread
         * 4. Create player and start playback on the main thread
         *
         */
        @Override
        public boolean onTune(Uri channelUri) {
            Log.d(TAG, "onTune: " + channelUri);

            // Notify Fire TV that tuning is in progress
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING);

            // Release any prior playback
            releasePlayer();

            // Store current channel for use in onUnblockContent
            mCurrentChannelUri = channelUri;

            long channelId = Long.parseLong(channelUri.getLastPathSegment());
            if (mTvInputManager.isParentalControlsEnabled()) {
                mBlockedRating = getContentRating(channelId);
                notifyContentBlocked(mBlockedRating);
                return true;
            }
            notifyContentAllowed();
            resolveAndPlay(channelId, channelUri);

            return true;
        }

        /**
         * Called when the user enters their PIN to unblock content.
         * Resolve the stream for the previously blocked channel and start playback.
         */
        @Override
        public void onUnblockContent(TvContentRating unblockedRating) {
            Log.d(TAG, "onUnblockContent: " + unblockedRating);
            if (unblockedRating.equals(mBlockedRating)) {
                mBlockedRating = null;
                notifyContentAllowed();

                final Uri channelUri = mCurrentChannelUri;
                if (channelUri != null) {
                    resolveAndPlay(Long.parseLong(channelUri.getLastPathSegment()), channelUri);
                }
            }
        }

        /**
         * Resolves the video URL for a channel on a background thread (resyncing the EPG once
         * if the first lookup fails) and starts playback on the main thread. Shared by onTune()'s
         * non-blocked path and onUnblockContent(), since a blocked tune skips this work until
         * the content is unblocked.
         */
        private void resolveAndPlay(long channelId, Uri channelUri) {
            mExecutor.execute(() -> {
                String videoUrl = resolveVideoUrl(channelId);

                // If no video URL found, the EPG may be stale (schedule expired).
                // Trigger a resync and retry once.
                if (videoUrl == null) {
                    Log.w(TAG, "No video URL for channel " + channelId + ", resyncing EPG");
                    SampleChannelManager.syncChannels(mContext);
                    videoUrl = resolveVideoUrl(channelId);
                }

                final String finalUrl = videoUrl;
                final long endTimeUtcMillis = queryCurrentProgramEndTime(channelId);
                mMainHandler.post(() -> {
                    // A newer onTune() may have superseded this one while resolution was
                    // running in the background — don't apply a stale result.
                    if (mReleased || !channelUri.equals(mCurrentChannelUri)) return;
                    if (finalUrl != null) {
                        createPlayer(Uri.parse(finalUrl));
                        scheduleNextProgramCheck(channelId, channelUri, endTimeUtcMillis);
                    } else {
                        Log.w(TAG, "Could not resolve video URL for channel " + channelId
                                + " even after resync");
                        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN);
                    }
                });
            });
        }

        /**
         * Schedules a re-check of this channel at the current program's scheduled end time, so
         * playback advances to the next program without the user needing to re-tune. Replaces
         * any previously scheduled check for this session.
         */
        private void scheduleNextProgramCheck(long channelId, Uri channelUri, long endTimeUtcMillis) {
            mMainHandler.removeCallbacks(mAdvanceToNextProgramRunnable);
            if (endTimeUtcMillis <= 0) {
                return;
            }
            mAdvanceToNextProgramRunnable = () -> {
                if (mReleased || !channelUri.equals(mCurrentChannelUri)) return;
                Log.d(TAG, "Program ended on channel " + channelId + ", advancing");
                resolveAndPlay(channelId, channelUri);
            };
            long delayMs = Math.max(endTimeUtcMillis - System.currentTimeMillis(), 0);
            mMainHandler.postDelayed(mAdvanceToNextProgramRunnable, delayMs);
        }

        @Override
        public void onRelease() {
            mReleased = true;
            releasePlayer();
            mExecutor.shutdownNow();
            mMainHandler.removeCallbacks(mAdvanceToNextProgramRunnable);
        }

        @Override
        public void onSetStreamVolume(float volume) {
            mVolume = volume;
            if (mPlayer != null) {
                mPlayer.setVolume(volume);
            }
        }

        @Override
        public void onSetCaptionEnabled(boolean enabled) {
            // No captions in this sample
        }

        /**
         * Returns the content rating for the currently-airing program on this channel.
         */
        private TvContentRating getContentRating(long channelId) {
            ContentResolver resolver = mContext.getContentResolver();
            long now = System.currentTimeMillis();
            Uri programUri = TvContract.buildProgramsUriForChannel(channelId, now, now + 1);

            try (Cursor cursor = resolver.query(programUri,
                    new String[]{TvContract.Programs.COLUMN_CONTENT_RATING},
                    null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    String ratingStr = cursor.getString(0);
                    if (ratingStr != null && !ratingStr.isEmpty()) {
                        return TvContentRating.unflattenFromString(ratingStr);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error querying content rating", e);
            }

            // Fallback — a rating is always required for notifyContentBlocked()
            return TvContentRating.createRating("com.android.tv", "US_TV", "US_TV_PG");
        }

        /**
         * Returns the current program's scheduled end time on the given channel, or -1 if
         * there's no current program. Used to schedule automatic advancement to the next
         * program (see scheduleNextProgramCheck()).
         */
        private long queryCurrentProgramEndTime(long channelId) {
            ContentResolver resolver = mContext.getContentResolver();
            // A 1ms window at "now" returns exactly the program airing at this instant
            // (its start_time <= now < end_time).
            long now = System.currentTimeMillis();
            Uri programUri = TvContract.buildProgramsUriForChannel(channelId, now, now + 1);

            try (Cursor cursor = resolver.query(programUri,
                    new String[]{TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS},
                    null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    return cursor.getLong(0);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error querying program end time", e);
            }

            return -1;
        }

        /**
         * Resolves the video URL for a given channel.
         *
         * Logic:
         * 1. Check channel's INTERNAL_PROVIDER_DATA for external metadata (Gracenote/Amazon catalog)
         *    → if present, use test stream URL
         * 2. Look up current program for the channel
         *    → get video URL from program's INTERNAL_PROVIDER_DATA
         * 3. If no program found, return null
         */
        private String resolveVideoUrl(long channelId) {
            ContentResolver resolver = mContext.getContentResolver();

            // Step 1: Check if this is an external metadata channel (Gracenote/Amazon catalog)
            Uri channelUri = TvContract.buildChannelUri(channelId);
            try (Cursor cursor = resolver.query(channelUri,
                    new String[]{TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA},
                    null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    byte[] data = cursor.getBlob(0);
                    if (data != null) {
                        String json = new String(data);
                        JSONObject channelData = new JSONObject(json);
                        String externalIdType = channelData.optString(KEY_EXTERNAL_ID_TYPE, null);
                        if (externalIdType != null) {
                            // External metadata channel (Gracenote or Amazon catalog) — play test stream
                            Log.d(TAG, "External metadata channel (" + externalIdType + "), using test stream");
                            return TEST_STREAM_URL;
                        }
                    }
                }
            } catch (JSONException e) {
                Log.e(TAG, "Error parsing channel internal provider data", e);
            }

            // Step 2: Look up current program for this channel
            // A 1ms window at "now" returns exactly the program airing at this instant
            // (its start_time <= now < end_time).
            long now = System.currentTimeMillis();
            Uri programUri = TvContract.buildProgramsUriForChannel(channelId, now, now + 1);
            try (Cursor cursor = resolver.query(programUri,
                    new String[]{TvContract.Programs.COLUMN_INTERNAL_PROVIDER_DATA},
                    null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    byte[] data = cursor.getBlob(0);
                    if (data != null) {
                        String json = new String(data);
                        JSONObject programData = new JSONObject(json);
                        String videoUrl = programData.optString(KEY_VIDEO_URL, null);
                        if (videoUrl != null) {
                            return videoUrl;
                        }
                    }
                }
            } catch (JSONException e) {
                Log.e(TAG, "Error parsing program internal provider data", e);
            }

            Log.w(TAG, "No video URL found for channel " + channelId);
            return null;
        }

        /**
         * Creates an ExoPlayer instance and starts playback on the provided Surface.
         * Notifies Fire TV when video becomes available.
         *
         */
        private void createPlayer(Uri videoUrl) {
            releasePlayer();

            mPlayer = new ExoPlayer.Builder(mContext).build();
            mPlayer.setVideoSurface(mSurface);
            mPlayer.setVolume(mVolume);
            mPlayer.addListener(new PlayerEventListener());
            mPlayer.setMediaItem(MediaItem.fromUri(videoUrl));
            mPlayer.setPlayWhenReady(true);
            mPlayer.prepare();

            // Logged for demo visibility only.
            Log.d(TAG, "Player created, preparing: " + videoUrl);
        }

        private void releasePlayer() {
            if (mPlayer != null) {
                mPlayer.setVideoSurface(null);
                mPlayer.stop();
                mPlayer.release();
                mPlayer = null;
            }
        }

        /**
         * Listens for player state changes and notifies Fire TV when playback is ready.
         *
         * 1. onTracksChanged() fires during prepare() — report tracks to Fire TV
         * 2. onPlaybackStateChanged(STATE_READY) fires when the player can play immediately —
         *    notify video available
         */
        private class PlayerEventListener implements Player.Listener {
            @Override
            public void onTracksChanged(Tracks tracks) {
                if (mPlayer == null) return;

                List<TvTrackInfo> tvTracks = new ArrayList<>();
                for (Tracks.Group trackGroup : tracks.getGroups()) {
                    for (int i = 0; i < trackGroup.length; i++) {
                        if (trackGroup.isTrackSelected(i)) {
                            Format format = trackGroup.getTrackFormat(i);
                            if (format.sampleMimeType != null) {
                                if (format.sampleMimeType.startsWith("video/")) {
                                    tvTracks.add(new TvTrackInfo.Builder(
                                            TvTrackInfo.TYPE_VIDEO, "video_0")
                                            .setVideoWidth(format.width)
                                            .setVideoHeight(format.height)
                                            .build());
                                } else if (format.sampleMimeType.startsWith("audio/")) {
                                    tvTracks.add(new TvTrackInfo.Builder(
                                            TvTrackInfo.TYPE_AUDIO, "audio_0")
                                            .setAudioChannelCount(format.channelCount)
                                            .setAudioSampleRate(format.sampleRate)
                                            .build());
                                }
                            }
                        }
                    }
                }

                notifyTracksChanged(tvTracks);
                for (TvTrackInfo track : tvTracks) {
                    notifyTrackSelected(track.getType(), track.getId());
                }
                notifyTrackSelected(TvTrackInfo.TYPE_SUBTITLE, null);
            }

            @Override
            public void onPlaybackStateChanged(@Player.State int playbackState) {
                if (mPlayer == null) return;

                if (playbackState == Player.STATE_READY && mPlayer.getPlayWhenReady()) {
                    notifyVideoAvailable();
                    Log.d(TAG, "Playback ready, notified Fire TV");
                } else if (playbackState == Player.STATE_BUFFERING) {
                    notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING);
                }
            }

            @Override
            public void onPlayerError(com.google.android.exoplayer2.PlaybackException error) {
                Log.e(TAG, "Player error: " + error.getMessage(), error);
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN);
            }
        }
    }
}
