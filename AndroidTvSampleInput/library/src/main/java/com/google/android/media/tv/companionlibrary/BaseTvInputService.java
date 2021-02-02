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

package com.google.android.media.tv.companionlibrary;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.media.PlaybackParams;
import android.media.tv.TvContentRating;
import android.media.tv.TvContract;
import android.media.tv.TvInputManager;
import android.media.tv.TvInputService;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.util.LongSparseArray;
import android.view.Surface;
import com.google.android.media.tv.companionlibrary.model.Channel;
import com.google.android.media.tv.companionlibrary.model.Program;
import com.google.android.media.tv.companionlibrary.utils.TvContractUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The BaseTvInputService provides helper methods to make it easier to create a
 * {@link TvInputService} with built-in methods for content blocking and pulling the current program
 * from the Electronic Programming Guide.
 */
public abstract class BaseTvInputService extends TvInputService {
    private static final String TAG = BaseTvInputService.class.getSimpleName();
    private static final boolean DEBUG = false;

    /**
     * Used for interacting with {@link SharedPreferences}.
     * @hide
     */
    public static final String PREFERENCES_FILE_KEY =
            "com.google.android.media.tv.companionlibrary";
    /**
     * Base key string used to identifying last played ad times for a channel
     * TODO This key will be shared by multiple Sessions (e.g. PIP)
     * @hide
     */
    public static final String SHARED_PREFERENCES_KEY_LAST_CHANNEL_AD_PLAY =
            "last_program_ad_time_ms";

    // For database calls
    private static HandlerThread mDbHandlerThread;

    // Map of channel {@link TvContract.Channels#_ID} to Channel objects
    private static LongSparseArray<Channel> mChannelMap;
    private static ContentResolver mContentResolver;
    private static ContentObserver mChannelObserver;

    // For content ratings
    private static final List<Session> mSessions = new ArrayList<>();
    private final BroadcastReceiver mParentalControlsBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            for (Session session : mSessions) {
                TvInputManager manager =
                        (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);

                if (!manager.isParentalControlsEnabled()) {
                    session.onUnblockContent(null);
                } else {
                    session.checkCurrentProgramContent();
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        // Create background thread
        mDbHandlerThread = new HandlerThread(getClass().getSimpleName());
        mDbHandlerThread.start();

        // Initialize the channel map and set observer for changes
        mContentResolver = BaseTvInputService.this.getContentResolver();
        updateChannelMap();
        mChannelObserver = new ContentObserver(new Handler(mDbHandlerThread.getLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                updateChannelMap();
            }
        };
        mContentResolver.registerContentObserver(TvContract.Channels.CONTENT_URI, true,
                mChannelObserver);

        // Setup our BroadcastReceiver
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TvInputManager.ACTION_BLOCKED_RATINGS_CHANGED);
        intentFilter.addAction(TvInputManager.ACTION_PARENTAL_CONTROLS_ENABLED_CHANGED);
        registerReceiver(mParentalControlsBroadcastReceiver, intentFilter);
    }

    private void updateChannelMap() {
        ComponentName component = new ComponentName(BaseTvInputService.this.getPackageName(),
                BaseTvInputService.this.getClass().getName());
        String inputId = TvContract.buildInputId(component);
        mChannelMap = TvContractUtils.buildChannelMap(mContentResolver, inputId);
    }

    /**
     * Adds the Session to the list of currently available sessions.
     * @param session The newly created session.
     * @return The session that was created.
     */
    public Session sessionCreated(Session session) {
        mSessions.add(session);
        return session;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mParentalControlsBroadcastReceiver);
        mContentResolver.unregisterContentObserver(mChannelObserver);
        mDbHandlerThread.quit();
        mDbHandlerThread = null;
    }

    /**
     * A {@link BaseTvInputService.Session} is called when a user tunes to channel provided by
     * this {@link BaseTvInputService}.
     */
    public static abstract class Session extends android.media.tv.TvInputService.Session implements Handler.Callback {
        private static final int MSG_PLAY_CONTENT = 1000;

        /** Minimum difference between playback time and system time in order for playback
         * to be considered non-live (timeshifted). */
        private static final long TIME_SHIFTED_MINIMUM_DIFFERENCE_MILLIS = 3000L;
        /** Buffer around current time for scheduling ads. If an ad will stop within this
         * amount of time relative to the current time, it is considered past and will not load.  */
        private static final long PAST_AD_BUFFER_MILLIS = 2000L;

        private final Context mContext;
        private final TvInputManager mTvInputManager;
        private Channel mCurrentChannel;
        private Program mCurrentProgram;
        private long mElapsedProgramTime;
        private long mTimeShiftedPlaybackPosition = TvInputManager.TIME_SHIFT_INVALID_TIME;
        private boolean mTimeShiftIsPaused;

        private TvContentRating mLastBlockedRating;
        private TvContentRating[] mCurrentContentRatingSet;

        private final Set<TvContentRating> mUnblockedRatingSet = new HashSet<>();
        private final Handler mDbHandler;
        private final Handler mHandler;
        private GetCurrentProgramRunnable mGetCurrentProgramRunnable;

        private Uri mChannelUri;
        private Surface mSurface;
        private float mVolume;

        public Session(Context context, String inputId) {
            super(context);
            this.mContext = context;
            mTvInputManager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
            mLastBlockedRating = null;
            mDbHandler = new Handler(mDbHandlerThread.getLooper());
            mHandler = new Handler(this);
        }

        @Override
        public void onRelease() {
            mDbHandler.removeCallbacksAndMessages(null);
            mHandler.removeCallbacksAndMessages(null);
            mSessions.remove(this);
        }

        /**
         * PLAYBACK-FTVUI 4: handleMessage sent by the runnable.
         * Once the current program that should be playing is found we set the current program and play it.
         */
        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PLAY_CONTENT:
                    mCurrentProgram = (Program) msg.obj;
                    playCurrentContent();
                    return true;
            }
            return false;
        }

        @Override
        public boolean onSetSurface(Surface surface) {
            setTvPlayerSurface(surface);
            mSurface = surface;
            return true;
        }

        private void setTvPlayerSurface(Surface surface) {
            if (getTvPlayer() != null) {
                getTvPlayer().setSurface(surface);
            }
        }

        @Override
        public void onSetStreamVolume(float volume) {
            setTvPlayerVolume(volume);
            mVolume = volume;
        }

        private void setTvPlayerVolume(float volume) {
            if (getTvPlayer() != null) {
                getTvPlayer().setVolume(volume);
            }
        }

        /**
         * PLAYBACK-FTVUI 2: onTune Base Class
         */
        @Override
        public boolean onTune(Uri channelUri) {
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING);

            mChannelUri = channelUri;
            long channelId = ContentUris.parseId(channelUri);
            Log.d(TAG, "OnTune fired. channelId: " + channelId);
            mCurrentChannel = mChannelMap.get(channelId);

            mTimeShiftedPlaybackPosition = TvInputManager.TIME_SHIFT_INVALID_TIME;

            if (mDbHandler != null) {
                mUnblockedRatingSet.clear();
                mDbHandler.removeCallbacks(mGetCurrentProgramRunnable);
                mGetCurrentProgramRunnable = new GetCurrentProgramRunnable(mChannelUri);
                mDbHandler.post(mGetCurrentProgramRunnable);
            }
            return true;
        }

        @Override
        public void onTimeShiftPause() {
            mDbHandler.removeCallbacks(mGetCurrentProgramRunnable);
            mTimeShiftIsPaused = true;
            if (getTvPlayer() != null) {
                getTvPlayer().pause();
            }
        }

        @Override
        public void onTimeShiftResume() {
            if (DEBUG) Log.d(TAG, "Resume playback of program");
            mTimeShiftIsPaused = false;
            if (mCurrentProgram == null) {
                return;
            }

            mElapsedProgramTime = getTvPlayer().getCurrentPosition();

            if (getTvPlayer() != null) {
                getTvPlayer().play();
            }
            // Resume and make sure media is playing at regular speed.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PlaybackParams normalParams = new PlaybackParams();
                normalParams.setSpeed(1);
                onTimeShiftSetPlaybackParams(normalParams);
            }
        }

        @RequiresApi(api = Build.VERSION_CODES.M)
        @Override
        public void onTimeShiftSeekTo(long timeMs) {
            if (DEBUG) Log.d(TAG, "Seeking to the position: " + timeMs);
            if (mCurrentProgram == null) {
                return;
            }

            mDbHandler.removeCallbacks(mGetCurrentProgramRunnable);

            // Update our handler because we have changed the playback time.
            if (getTvPlayer() != null) {
                // Shortcut for switching to live playback.
                if (timeMs > System.currentTimeMillis() -
                        TIME_SHIFTED_MINIMUM_DIFFERENCE_MILLIS) {
                    mTimeShiftedPlaybackPosition = TvInputManager.TIME_SHIFT_INVALID_TIME;
                    playCurrentContent();
                    return;
                }

                mTimeShiftedPlaybackPosition = timeMs;
                // Elapsed ad time and program time will need to be recalculated
                // as if we just tuned to the channel at mTimeShiftPlaybackPosition.
                calculateElapsedTimesFromCurrentTime();
                scheduleNextProgram();
                getTvPlayer().seekTo(mElapsedProgramTime);
                onTimeShiftGetCurrentPosition();

                // After adjusting necessary elapsed playback times based on new
                // time shift position, content should not continue to play if previously
                // in a paused state.
                if (mTimeShiftIsPaused) {
                    onTimeShiftPause();
                }
            }
        }

        @RequiresApi(api = Build.VERSION_CODES.M)
        @Override
        public long onTimeShiftGetStartPosition() {
            if (mCurrentProgram != null) {
                return mCurrentProgram.getStartTimeUtcMillis();
            }
            return TvInputManager.TIME_SHIFT_INVALID_TIME;
        }

        @RequiresApi(api = Build.VERSION_CODES.M)
        @Override
        public long onTimeShiftGetCurrentPosition() {
            if (getTvPlayer() != null && mCurrentProgram != null) {
                mElapsedProgramTime = getTvPlayer().getCurrentPosition();
                mTimeShiftedPlaybackPosition = mElapsedProgramTime +
                        mCurrentProgram.getStartTimeUtcMillis();
                if (DEBUG) {
                    Log.d(TAG, "Time Shift Current Position");
                    Log.d(TAG, "Elapsed program time: " + mElapsedProgramTime);
                    Log.d(TAG, "Total elapsed time: " + (mTimeShiftedPlaybackPosition -
                            mCurrentProgram.getStartTimeUtcMillis()));
                    Log.d(TAG, "Time shift difference: " + (System.currentTimeMillis() -
                            mTimeShiftedPlaybackPosition));
                    Log.d(TAG, "============================");
                }
                return getCurrentTime();
            }
            return TvInputManager.TIME_SHIFT_INVALID_TIME;
        }

        @RequiresApi(api = Build.VERSION_CODES.M)
        @Override
        public void onTimeShiftSetPlaybackParams(PlaybackParams params) {
            if (params.getSpeed() != 1.0f) {
                mDbHandler.removeCallbacks(mGetCurrentProgramRunnable);
            }

            if (DEBUG) {
                Log.d(TAG, "Set playback speed to " + params.getSpeed());
            }
            if (getTvPlayer() != null) {
                getTvPlayer().setPlaybackParams(params);
            }
        }

        @RequiresApi(api = Build.VERSION_CODES.M)
        @Override
        public void onTimeShiftPlay(Uri recordedProgramUri) {
            //No-op
        }

        /**
         * This method is called when the currently playing program has been blocked by parental
         * controls. Developers should release their {@link TvPlayer} immediately so unwanted
         * content is not displayed.
         *
         * @param rating The rating for the program that was blocked.
         */
        public void onBlockContent(TvContentRating rating) {
        }

        @Override
        public void onUnblockContent(TvContentRating rating) {
            // If called with null, parental controls are off.
            if (rating == null) {
                mUnblockedRatingSet.clear();
            }
            unblockContent(rating);
            playCurrentContent();
        }

        private boolean checkCurrentProgramContent() {
            mCurrentContentRatingSet = (mCurrentProgram == null
                    || mCurrentProgram.getContentRatings() == null
                    || mCurrentProgram.getContentRatings().length == 0) ? null :
                    mCurrentProgram.getContentRatings();
            return blockContentIfNeeded();
        }

        private long getCurrentTime() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                long timeShiftedDifference = System.currentTimeMillis() -
                        mTimeShiftedPlaybackPosition;
                if (mTimeShiftedPlaybackPosition != TvInputManager.TIME_SHIFT_INVALID_TIME &&
                        timeShiftedDifference > TIME_SHIFTED_MINIMUM_DIFFERENCE_MILLIS) {
                    return mTimeShiftedPlaybackPosition;
                }
            }
            mTimeShiftedPlaybackPosition = TvInputManager.TIME_SHIFT_INVALID_TIME;
            return System.currentTimeMillis();
        }

        private void scheduleNextProgram() {
            mDbHandler.removeCallbacks(mGetCurrentProgramRunnable);
            mDbHandler.postDelayed(mGetCurrentProgramRunnable,
                    mCurrentProgram.getEndTimeUtcMillis() - getCurrentTime());
        }

        /**
         * PLAYBACK-FTVUI 5: playCurrentContent()
         * This method handles parental control checks and then plays the current program if possible.
         */
        private void playCurrentContent() {
            if (mTvInputManager.isParentalControlsEnabled() && !checkCurrentProgramContent()) {
                scheduleNextProgram();
                return;
            }

            if (playCurrentProgram()) {
                setTvPlayerSurface(mSurface);
                setTvPlayerVolume(mVolume);
                if (mCurrentProgram != null) {
                    // Prepare to play the upcoming program.
                    scheduleNextProgram();
                }
            }
        }

        private void calculateElapsedTimesFromCurrentTime() {
            long currentTimeMs = getCurrentTime();
            mElapsedProgramTime = currentTimeMs - mCurrentProgram.getStartTimeUtcMillis();
        }

        /**
         * PLAYBACK-FTVUI 6: playCurrentProgram()
         * This method checks for the proper playback time and invokes onPlayProgram() to actually start
         * the player. 
         */
        private boolean playCurrentProgram() {
            if (mCurrentProgram == null) {
                Log.w(TAG, "Failed to get program info for " + mChannelUri + ". Try to do an " +
                            "EPG sync.");
                return onPlayProgram(null, 0);
            }
            calculateElapsedTimesFromCurrentTime();
            return onPlayProgram(mCurrentProgram, mElapsedProgramTime);
        }

        private void playCurrentChannel() {
            onPlayChannel(mCurrentChannel);
            playCurrentContent();
        }

        /**
         * Return the current {@link TvPlayer}.
         */
        public abstract TvPlayer getTvPlayer();

        /**
         * This method is called when a particular program is to begin playing at the particular
         * position. If there is not a program scheduled in the EPG, the parameter will be
         * {@code null}. Developers should check the null condition and handle that case, possibly
         * by manually resyncing the EPG.
         *
         * @param program The program that is set to be playing for a the currently tuned channel.
         * @param startPosMs Start position of content video.
         * @return Whether playing this program was successful.
         */
        public abstract boolean onPlayProgram(Program program, long startPosMs);

        /**
         * This method is called when the user tunes to a given channel. Developers can override
         * this if they want specific behavior to occur after the user tunes but before the program
         * or channel ad begins playing.
         *
         * @param channel The channel that the user wants to watch.
         */
        public void onPlayChannel(Channel channel) {
            // Do nothing.
        }

        public Uri getCurrentChannelUri() {
            return mChannelUri;
        }

        private boolean blockContentIfNeeded() {
            if (mCurrentContentRatingSet == null || !mTvInputManager.isParentalControlsEnabled()) {
                // Content rating is invalid so we don't need to block anymore.
                // Unblock content here explicitly to resume playback.
                unblockContent(null);
                return true;
            }
            // Check each content rating that the program has.
            TvContentRating blockedRating = null;
            for (TvContentRating contentRating : mCurrentContentRatingSet) {

                /**
                 * In here, "isRatingBlocked()" could be replaced with "mTvInputManager.isRatingBlocked()"
                 * Suggest to check mTvInputManager.isRatingBlocked() first, if the default TvInputManager API doesn't work
                 * Then please use "isRatingBlocked()"
                 */
                if (isRatingBlocked(contentRating) && !mUnblockedRatingSet.contains(contentRating)) {
                    // This should be blocked.
                    blockedRating = contentRating;
                }
            }
            if (blockedRating == null) {
                // Content rating is null so we don't need to block anymore.
                // Unblock content here explicitly to resume playback.
                unblockContent(null);
                return true;
            }
            mLastBlockedRating = blockedRating;
            // Children restricted content might be blocked by TV app as well,
            // but TIS should do its best not to show any single frame of blocked content.
            onBlockContent(blockedRating);
            notifyContentBlocked(blockedRating);
            if (mTimeShiftedPlaybackPosition != TvInputManager.TIME_SHIFT_INVALID_TIME) {
                onTimeShiftPause();
            }
            return false;
        }

        private void unblockContent(TvContentRating rating) {
            // TIS should unblock content only if unblock request is legitimate.
            if (rating == null || mLastBlockedRating == null || rating.equals(mLastBlockedRating)) {
                mLastBlockedRating = null;
                if (rating != null) {
                    mUnblockedRatingSet.add(rating);
                }
                notifyContentAllowed();
            }
        }

        /**
         * The method to check whether the program should be blocked or not given a Content Rating
         * Developers can implemented its own checking logic if the standard TvInputManager.isRatingBlocked() doesn't work
         * Here the api is just for hack demo
         * @param rating The standard Android Content Rating
         * @return whether this program should be blocked or not
         */
        private boolean isRatingBlocked(final TvContentRating rating){
            final Program currentProgram = TvContractUtils.getCurrentProgram(mContext.getContentResolver(),getCurrentChannelUri());

            if(currentProgram != null){
                final TvContentRating[] programContentRating = currentProgram.getContentRatings();

                for(TvContentRating contentRating : programContentRating){
                    if(contentRating.equals(rating) || contentRating.contains(rating) || rating.contains(contentRating)){
                        return true;
                    }
                }
            }

            return false;
        }

        private class GetCurrentProgramRunnable implements Runnable {
            private final Uri mChannelUri;

             GetCurrentProgramRunnable(Uri channelUri) {
                mChannelUri = channelUri;
            }

            /**
             * PLAYBACK-FTVUI 3: run GetCurrentProgramRunnable
             * This runnable finds the current program according to the schedule and invokes playback.
             * This is implemented as a runnable since it interacts with the TIF database directly to find the 
             * current playing program.
             */
            @Override
            public void run() {
                ContentResolver resolver = mContext.getContentResolver();
                Program program = null;
                long timeShiftedDifference = System.currentTimeMillis() -
                        mTimeShiftedPlaybackPosition;
                if (mTimeShiftedPlaybackPosition != TvInputManager.TIME_SHIFT_INVALID_TIME &&
                        timeShiftedDifference > TIME_SHIFTED_MINIMUM_DIFFERENCE_MILLIS) {
                    program = TvContractUtils.getNextProgram(resolver, mChannelUri,
                            mCurrentProgram);
                } else {
                    mTimeShiftedPlaybackPosition = TvInputManager.TIME_SHIFT_INVALID_TIME;
                    program = TvContractUtils.getCurrentProgram(resolver, mChannelUri);
                }
                mHandler.removeMessages(MSG_PLAY_CONTENT);
                mHandler.obtainMessage(MSG_PLAY_CONTENT, program).sendToTarget();
            }
        }
    }
}
