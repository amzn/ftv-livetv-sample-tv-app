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
import android.graphics.Point;
import android.media.tv.TvContentRating;
import android.media.tv.TvInputManager;
import android.media.tv.TvInputService;
import android.media.tv.TvTrackInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.CaptioningManager;
import com.example.android.sampletvinput.R;
import com.example.android.sampletvinput.SampleJobService;
import com.example.android.sampletvinput.player.DemoPlayer;
import com.example.android.sampletvinput.player.RendererBuilderFactory;
import com.google.android.exoplayer.ExoPlayer;
import com.google.android.exoplayer.MediaFormat;
import com.google.android.exoplayer.text.CaptionStyleCompat;
import com.google.android.exoplayer.text.Cue;
import com.google.android.exoplayer.text.SubtitleLayout;
import com.google.android.exoplayer.util.Util;
import com.google.android.media.tv.companionlibrary.BaseTvInputService;
import com.google.android.media.tv.companionlibrary.EpgSyncJobService;
import com.google.android.media.tv.companionlibrary.TvPlayer;
import com.google.android.media.tv.companionlibrary.model.Channel;
import com.google.android.media.tv.companionlibrary.model.InternalProviderData;
import com.google.android.media.tv.companionlibrary.model.Program;

import java.util.ArrayList;
import java.util.List;

/**
 * TvInputService which provides a full implementation of EPG, subtitles, multi-audio, parental
 * controls, and overlay view.
 */
public class RichTvInputService extends BaseTvInputService {
    private static final String TAG = "RichTvInputService";
    private static final boolean DEBUG = false;
    private static final long EPG_SYNC_DELAYED_PERIOD_MS = 1000 * 2; // 2 Seconds

    private CaptioningManager mCaptioningManager;

    /**
     * Gets the track id of the track type and track index.
     *
     * @param trackType  the type of the track e.g. TvTrackInfo.TYPE_AUDIO
     * @param trackIndex the index of that track within the media. e.g. 0, 1, 2...
     * @return the track id for the type & index combination.
     */
    private static String getTrackId(int trackType, int trackIndex) {
        return trackType + "-" + trackIndex;
    }

    /**
     * Gets the index of the track for a given track id.
     *
     * @param trackId the track id.
     * @return the track index for the given id, as an integer.
     */
    private static int getIndexFromTrackId(String trackId) {
        return Integer.parseInt(trackId.split("-")[1]);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mCaptioningManager = (CaptioningManager) getSystemService(Context.CAPTIONING_SERVICE);
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
    class RichTvInputSessionImpl extends BaseTvInputService.Session implements
            DemoPlayer.Listener, DemoPlayer.CaptionListener {
        private static final float CAPTION_LINE_HEIGHT_RATIO = 0.0533f;
        private static final int TEXT_UNIT_PIXELS = 0;
        private static final String UNKNOWN_LANGUAGE = "und";

        private static final String GRACENOTE_ID = "gracenote_ontv";

        // Example MPEG-DASH test content from Telecom Paris. Stream is licensed Creative Commons.
        // See http://download.tsi.telecom-paristech.fr/gpac/dataset/dash/uhd/ for more information.
        private static final String GRACENOTE_TEST_STREAM_URL = "http://download.tsi.telecom-paristech.fr/gpac/DASH_CONFORMANCE/TelecomParisTech/mp4-live/mp4-live-mpd-AV-NBS.mpd";

        private int mSelectedSubtitleTrackIndex;
        private SubtitleLayout mSubtitleView;
        private DemoPlayer mPlayer;
        private boolean mCaptionEnabled;
        private String mInputId;
        private Context mContext;

        RichTvInputSessionImpl(Context context, String inputId) {
            super(context, inputId);
            mCaptionEnabled = mCaptioningManager.isEnabled();
            mContext = context;
            mInputId = inputId;
        }

        @Override
        public View onCreateOverlayView() {
            LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
            mSubtitleView = (SubtitleLayout) inflater.inflate(R.layout.subtitleview, null);

            // Configure the subtitle view.
            CaptionStyleCompat captionStyle;
            float captionTextSize = getCaptionFontSize();
            captionStyle = CaptionStyleCompat
                    .createFromCaptionStyle(mCaptioningManager.getUserStyle());
            captionTextSize *= mCaptioningManager.getFontScale();
            mSubtitleView.setStyle(captionStyle);
            mSubtitleView.setFixedTextSize(TEXT_UNIT_PIXELS, captionTextSize);
            mSubtitleView.setVisibility(View.VISIBLE);

            return mSubtitleView;
        }

        private List<TvTrackInfo> getAllTracks() {
            String trackId;
            List<TvTrackInfo> tracks = new ArrayList<>();

            int[] trackTypes = {
                    DemoPlayer.TYPE_AUDIO,
                    DemoPlayer.TYPE_VIDEO,
                    DemoPlayer.TYPE_TEXT
            };

            for (int trackType : trackTypes) {
                int count = mPlayer.getTrackCount(trackType);
                for (int i = 0; i < count; i++) {
                    MediaFormat format = mPlayer.getTrackFormat(trackType, i);
                    trackId = getTrackId(trackType, i);
                    TvTrackInfo.Builder builder = new TvTrackInfo.Builder(trackType, trackId);

                    if (trackType == DemoPlayer.TYPE_VIDEO) {
                        if (format.maxWidth != MediaFormat.NO_VALUE) {
                            builder.setVideoWidth(format.maxWidth);
                        } else if (format.width != MediaFormat.NO_VALUE) {
                            builder.setVideoWidth(format.width);
                        }
                        if (format.maxHeight != MediaFormat.NO_VALUE) {
                            builder.setVideoHeight(format.maxHeight);
                        } else if (format.height != MediaFormat.NO_VALUE) {
                            builder.setVideoHeight(format.height);
                        }
                    } else if (trackType == DemoPlayer.TYPE_AUDIO) {
                        builder.setAudioChannelCount(format.channelCount);
                        builder.setAudioSampleRate(format.sampleRate);
                        if (format.language != null && !UNKNOWN_LANGUAGE.equals(format.language)) {
                            // TvInputInfo expects {@code null} for unknown language.
                            builder.setLanguage(format.language);
                        }
                    } else if (trackType == DemoPlayer.TYPE_TEXT) {
                        if (format.language != null && !UNKNOWN_LANGUAGE.equals(format.language)) {
                            // TvInputInfo expects {@code null} for unknown language.
                            builder.setLanguage(format.language);
                        }
                    }

                    tracks.add(builder.build());
                }
            }
            return tracks;
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
            if (programExists)
            {
                // The feed for the program is stored in its internalProviderData column.
                createPlayer(program.getInternalProviderData().getVideoType(),
                        Uri.parse(program.getInternalProviderData().getVideoUrl()));
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
            if (canPlayFromExternalCatalogData)
            {
                // A simple test stream is played for all Gracenote channels. A production-ready implementation
                // would obviously be more complex and would need to select the appropriate stream for the channel.
                createPlayer(
                        Util.TYPE_DASH,
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
            return mPlayer;
        }

        /**
         * onTune is the initial call made by Fire TV into the app when the user triggers 
         * playback in the Fire TV UI. Follow these comments tagged with "PLAYBACK-FTVUI" to trace the full
         * flow of playback as implemented in this sample app.
         * 
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

        /**
         * PLAYBACK-FTVUI 8: createPlayer()
         * Creates the player with the specific video url for the program.
         */
        private void createPlayer(int videoType, Uri videoUrl) {
            releasePlayer();
            mPlayer = new DemoPlayer(RendererBuilderFactory.createRendererBuilder(
                    mContext, videoType, videoUrl));
            mPlayer.addListener(this);
            mPlayer.setCaptionListener(this);
            mPlayer.prepare();
        }

        @Override
        public void onSetCaptionEnabled(boolean enabled) {
            mCaptionEnabled = enabled;
            if (mPlayer != null) {
                if (mCaptionEnabled) {
                    mPlayer.setSelectedTrack(TvTrackInfo.TYPE_SUBTITLE,
                            mSelectedSubtitleTrackIndex);
                } else {
                    mPlayer.setSelectedTrack(TvTrackInfo.TYPE_SUBTITLE, DemoPlayer.TRACK_DISABLED);
                }
            }
        }

        @Override
        public boolean onSelectTrack(int type, String trackId) {
            if (trackId == null) {
                return true;
            }

            int trackIndex = getIndexFromTrackId(trackId);
            if (mPlayer != null) {
                if (type == TvTrackInfo.TYPE_SUBTITLE) {
                    if (! mCaptionEnabled) {
                        return false;
                    }
                    mSelectedSubtitleTrackIndex = trackIndex;
                }

                mPlayer.setSelectedTrack(type, trackIndex);
                notifyTrackSelected(type, trackId);
                return true;
            }
            return false;
        }

        private void releasePlayer() {
            if (mPlayer != null) {
                mPlayer.removeListener(this);
                mPlayer.setSurface(null);
                mPlayer.stop();
                mPlayer.release();
                mPlayer = null;
            }
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

        private float getCaptionFontSize() {
            Display display = ((WindowManager) getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay();
            Point displaySize = new Point();
            display.getSize(displaySize);
            return Math.max(getResources().getDimension(R.dimen.subtitle_minimum_font_size),
                    CAPTION_LINE_HEIGHT_RATIO * Math.min(displaySize.x, displaySize.y));
        }

        /**
         * PLAYBACK-FTVUI 9: onStateChanged()
         * Triggered by the DemoPlayer once playback begins. Invokes critical notifications back to Fire TV UI to complete the flow.
         */
        @Override
        public void onStateChanged(boolean playWhenReady, int playbackState) {
            if (mPlayer == null) {
                return;
            }

            if (playWhenReady && playbackState == ExoPlayer.STATE_READY) {
                notifyTracksChanged(getAllTracks());
                String audioId = getTrackId(TvTrackInfo.TYPE_AUDIO,
                        mPlayer.getSelectedTrack(TvTrackInfo.TYPE_AUDIO));
                String videoId = getTrackId(TvTrackInfo.TYPE_VIDEO,
                        mPlayer.getSelectedTrack(TvTrackInfo.TYPE_VIDEO));
                String textId = getTrackId(TvTrackInfo.TYPE_SUBTITLE,
                        mPlayer.getSelectedTrack(TvTrackInfo.TYPE_SUBTITLE));

                notifyTrackSelected(TvTrackInfo.TYPE_AUDIO, audioId);
                notifyTrackSelected(TvTrackInfo.TYPE_VIDEO, videoId);
                notifyTrackSelected(TvTrackInfo.TYPE_SUBTITLE, textId);
                // These notifications alert the Fire TV UI that the player is ready to playback
                // These two calls must fire only once the player is fully ready to play
                notifyVideoAvailable();
                notifyContentAllowed();
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    Math.abs(mPlayer.getPlaybackSpeed() - 1) < 0.1 &&
                    playWhenReady && playbackState == ExoPlayer.STATE_BUFFERING) {
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING);
            }
        }

        @Override
        public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees,
                float pixelWidthHeightRatio) {
            // Do nothing.
        }

        @Override
        public void onError(Exception e) {
            Log.e(TAG, e.getMessage());
        }

        @Override
        public void onCues(List<Cue> cues) {
            mSubtitleView.setCues(cues);
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
    }
}
