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

package com.google.android.media.tv.companionlibrary.utils;


import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContentRating;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.SparseArray;

import com.google.android.media.tv.companionlibrary.model.Channel;
import com.google.android.media.tv.companionlibrary.model.Program;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Static helper methods for working with {@link android.media.tv.TvContract}.
 */
public class TvContractUtils {

    /**
     * Indicates that no source type has been defined for this video yet
     */
    public static final int SOURCE_TYPE_INVALID = -1;
    /**
     * Indicates that the video will use MPEG-DASH (Dynamic Adaptive Streaming over HTTP) for
     * playback.
     */
    public static final int SOURCE_TYPE_MPEG_DASH = 0;
    /**
     * Indicates that the video will use SS (Smooth Streaming) for playback.
     */
    public static final int SOURCE_TYPE_SS = 1;
    /**
     * Indicates that the video will use HLS (HTTP Live Streaming) for playback.
     */
    public static final int SOURCE_TYPE_HLS = 2;
    /**
     * Indicates that the video will use HTTP Progressive for playback.
     */
    public static final int SOURCE_TYPE_HTTP_PROGRESSIVE = 3;

    /**
     * The Input ID when the Sample Input Service initialized the service
     */
    public static final String INPUT_ID = "com.example.android.sampletvinput/.rich.RichTvInputService";

    private static final String TAG = "TvContractUtils";
    private static final boolean DEBUG = true;
    private static final SparseArray<String> VIDEO_HEIGHT_TO_FORMAT_MAP = new SparseArray<>();

    /**
     * Here is the Mock data for Tif, if the app uses the tif as the only source, then it should be
     * responsible for pushing channel and program metadata as well
     */
    static {
        VIDEO_HEIGHT_TO_FORMAT_MAP.put(480, TvContract.Channels.VIDEO_FORMAT_480P);
        VIDEO_HEIGHT_TO_FORMAT_MAP.put(576, TvContract.Channels.VIDEO_FORMAT_576P);
        VIDEO_HEIGHT_TO_FORMAT_MAP.put(720, TvContract.Channels.VIDEO_FORMAT_720P);
        VIDEO_HEIGHT_TO_FORMAT_MAP.put(1080, TvContract.Channels.VIDEO_FORMAT_1080P);
        VIDEO_HEIGHT_TO_FORMAT_MAP.put(2160, TvContract.Channels.VIDEO_FORMAT_2160P);
        VIDEO_HEIGHT_TO_FORMAT_MAP.put(4320, TvContract.Channels.VIDEO_FORMAT_4320P);
    }

    /**
     * This constants is used for deep links
     * This is the primary constant that is used to get the Channel Information from the intent
     * In here, the key-value pair in the intent is "channel_key" and channel's originalNetworkId
     */
    public static final String CHANNEL_DEEP_LINK_INTENT_PRIM_KEY = "channel_deep_link_intent_prim_key";

    /**
     * This constants is used for deep links
     * This the secondary constant that is to get the Channel Input information from the intent
     * In here, the key-value pair in the intent is "channel_key" and channel's inputId
     */
    public static final String CHANNEL_DEEP_LINK_INTENT_SEC_KEY = "channel_deep_link_intent_sec_key";

    /**
     * Updates the list of available channels in the TIF database. This method relies on the
     * original network ID field of the object being set with a unique ID to recognize channels
     * previously inserted. Fire TV does not use this field so the app is free to use it as needed.
     *
     * @param context  The application's context.
     * @param inputId  The ID of the TV input service that provides this TV channel.
     * @param channels The updated list of channels. The Provider should be responsible to figure out
     *                 its own way to collecting those channels. Referring to the method in "getChannels()" in SampleJobService.java
     */
    public static void updateChannelsWithTif(Context context, String inputId, List<Channel> channels) {
        ContentResolver contentResolver = context.getContentResolver();
        processChannels(contentResolver, channels, inputId, context.getPackageName());
        updateChannelMetadata(contentResolver, channels, context);
    }

    /**
     * Helper method to encapsulate the logic for updating, adding and deleting channels in the TIF database.
     * Comparison of Channel object in DB vs Channel object from Provider is done
     * via Channel equals() method.
     *
     * @param contentResolver Handles query operations against TIF database
     * @param channels        List of Channel objects sent by the Provider
     * @param inputId         The ID of the TV input service that provides this TV channel.
     */
    private static void processChannels(ContentResolver contentResolver, List<Channel> channels, String inputId, String packageName) {
        // Query channels from TIF
        List<Channel> tifChannels = ChannelDao.getAllChannels(contentResolver);

        // Provider channels (desired) map (originalNetworkId -> Channel object)
        LongSparseArray<Channel> desiredChannels = buildChannelMap(channels);

        // Channels to be updated
        LongSparseArray<Channel> updatedChannels = new LongSparseArray<>();

        // Channels to be deleted
        List<Long> deletedChannelIds = new ArrayList<>();

        // null cursor means TIF state is unknown
        if (tifChannels == null) {
            // If a channel has been removed from the TIF Channel table,
            // Live TV will handle the removal of the hanging metadata in TifExtension
            ChannelDao.deleteAllChannels(contentResolver);
        } else {
            for (Channel tifChannel : tifChannels) {
                Channel lookupChannel = desiredChannels.get(tifChannel.getOriginalNetworkId());
                if (lookupChannel != null) {
                    // remove so its not included in the bulk insert at the end
                    desiredChannels.remove(lookupChannel.getOriginalNetworkId());

                    // check for difference between database (tifChannel) and
                    // channel from provider (lookupChannel)
                    if (!tifChannel.equals(lookupChannel)) {
                        Log.d(TAG, "Channel with network id " + tifChannel.getOriginalNetworkId() + ", row id " + tifChannel.getId() + " needs to be updated");
                        Log.d(TAG, "Channel from DB: " + tifChannel.toString());
                        Log.d(TAG, "Channel from provider: " + lookupChannel.toString());

                        Channel updatedChanel = new Channel.Builder(lookupChannel)
                                .setId(tifChannel.getId())
                                .build();
                        updatedChannels.put(updatedChanel.getOriginalNetworkId(), updatedChanel);
                    } else {
                        Log.d(TAG, "Channel with network id " + tifChannel.getOriginalNetworkId() + " is already up to date");
                    }
                } else {
                    // Channel exists in DB but not in channels sent by provider so delete is required
                    Log.d(TAG, "Channel with network id " + tifChannel.getOriginalNetworkId() + " needs to be deleted");
                    deletedChannelIds.add(tifChannel.getId());
                }
            }
        }

        ChannelDao.upsertChannels(
                contentResolver,
                desiredChannels,
                updatedChannels,
                inputId,
                packageName
        );

        ChannelDao.deleteChannels(
                contentResolver,
                deletedChannelIds
        );
    }

    /**
     * Helper method to handle inserting logos for channels and updating the TIF extension db with
     * genre information. This function relies on channels being present and up to date in the TIF
     * channel table
     *
     * @param contentResolver Handles query operations against TIF database
     * @param channels        List of channels sent by the Provider
     * @param context         The application's context
     */
    private static void updateChannelMetadata(ContentResolver contentResolver, List<Channel> channels, Context context) {
        List<Channel> currentTifChannels = ChannelDao.getAllChannels(contentResolver);
        LongSparseArray<Channel> desiredChannels = buildChannelMap(channels);

        if (currentTifChannels != null) {
            //Insert channel logos
            insertChannelLogos(context, currentTifChannels, desiredChannels);

            List<TifExtensionChannel> tifExtensionChannels = ConverterUtils
                    .convertToTifExtensionChannel(currentTifChannels, desiredChannels);

            // Bulk insert tif extension channels
            ChannelDao.insertTifExtensionChannels(
                    contentResolver,
                    tifExtensionChannels
            );
        }
    }

    private static void insertChannelLogos(Context context, List<Channel> channels, LongSparseArray<Channel> desiredChannels) {
        Map<Uri, String> logos = new HashMap<>();
        for (Channel channel : channels) {
            Channel lookupChannel = desiredChannels.get(channel.getOriginalNetworkId());
            // Check if the channel has a logo associated with it
            // get row id of channel to determine URI
            if (lookupChannel != null && !TextUtils.isEmpty(lookupChannel.getChannelLogo())) {
                Log.d(TAG, "Adding logo for channel with network id " + channel.getOriginalNetworkId());
                logos.put(TvContract.buildChannelLogoUri(channel.getId()), lookupChannel.getChannelLogo());
            }
        }

        if (!logos.isEmpty()) {
            new InsertLogosTask(context).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, logos);
        }
    }

    private static LongSparseArray<Channel> buildChannelMap(List<Channel> channels) {
        LongSparseArray<Channel> channelMap = new LongSparseArray<>();

        for (Channel channel : channels) {
            channelMap.put(channel.getOriginalNetworkId(), channel);
        }

        return channelMap;
    }

    /**
     * Builds a map of available channels.
     *
     * @param resolver Application's ContentResolver.
     * @param inputId  The ID of the TV input service that provides this TV channel.
     * @return LongSparseArray mapping each channel's {@link TvContract.Channels#_ID} to the
     * Channel object.
     * @hide
     */
    public static LongSparseArray<Channel> buildChannelMap(@NonNull ContentResolver resolver,
                                                           @NonNull String inputId) {
        Uri uri = TvContract.buildChannelsUriForInput(inputId);
        LongSparseArray<Channel> channelMap = new LongSparseArray<>();
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, Channel.PROJECTION, null, null, null);
            if (cursor == null || cursor.getCount() == 0) {
                if (DEBUG) {
                    Log.d(TAG, "Cursor is null or found no results");
                }
                return null;
            }

            while (cursor.moveToNext()) {
                Channel nextChannel = Channel.fromCursor(cursor);
                channelMap.put(nextChannel.getId(), nextChannel);
            }
        } catch (Exception e) {
            Log.d(TAG, "Content provider query: " + Arrays.toString(e.getStackTrace()));
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return channelMap;
    }

    /**
     * Returns the current list of programs on a given channel.
     *
     * @param resolver   Application's ContentResolver.
     * @param channelUri Channel's Uri.
     * @return List of programs.
     * @hide
     */
    public static List<Program> getPrograms(ContentResolver resolver, Uri channelUri) {
        if (channelUri == null) {
            return null;
        }
        Uri uri = TvContract.buildProgramsUriForChannel(channelUri);
        List<Program> programs = new ArrayList<>();
        // TvProvider returns programs in chronological order by default.
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, Program.PROJECTION, null, null, null);
            if (cursor == null || cursor.getCount() == 0) {
                return programs;
            }
            while (cursor.moveToNext()) {
                programs.add(Program.fromCursor(cursor));
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to get programs for " + channelUri, e);
        } finally {
            if (cursor != null) {
                cursor.close();

            }
        }
        return programs;
    }

    /**
     * Returns the program that is scheduled to be playing now on a given channel.
     *
     * @param resolver   Application's ContentResolver.
     * @param channelUri Channel's Uri.
     * @return The program that is scheduled for now in the EPG.
     */
    public static Program getCurrentProgram(ContentResolver resolver, Uri channelUri) {
        List<Program> programs = getPrograms(resolver, channelUri);
        if (programs == null) {
            return null;
        }
        long nowMs = System.currentTimeMillis();
        for (Program program : programs) {
            if (program.getStartTimeUtcMillis() <= nowMs && program.getEndTimeUtcMillis() > nowMs) {
                return program;
            }
        }
        return null;
    }

    /**
     * Returns the program that is scheduled to be playing after a given program on a given channel.
     *
     * @param resolver       Application's ContentResolver.
     * @param channelUri     Channel's Uri.
     * @param currentProgram Program which plays before the desired program.If null, returns current
     *                       program
     * @return The program that is scheduled after given program in the EPG.
     */
    public static Program getNextProgram(ContentResolver resolver, Uri channelUri,
                                         Program currentProgram) {
        if (currentProgram == null) {
            return getCurrentProgram(resolver, channelUri);
        }
        List<Program> programs = getPrograms(resolver, channelUri);
        if (programs == null) {
            return null;
        }
        int currentProgramIndex = programs.indexOf(currentProgram);
        if (currentProgramIndex + 1 < programs.size()) {
            return programs.get(currentProgramIndex + 1);
        }
        return null;
    }

    private static void insertUrl(Context context, Uri contentUri, URL sourceUrl) {
        if (DEBUG) {
            Log.d(TAG, "Inserting " + sourceUrl + " to " + contentUri);
        }
        InputStream is = null;
        OutputStream os = null;
        try {
            is = sourceUrl.openStream();
            os = context.getContentResolver().openOutputStream(contentUri);
            copy(is, os);
        } catch (IOException ioe) {
            Log.e(TAG, "Failed to write " + sourceUrl + "  to " + contentUri, ioe);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    // Ignore exception.
                }
            }
            if (os != null) {
                try {
                    os.close();
                } catch (IOException e) {
                    // Ignore exception.
                }
            }
        }
    }

    private static void copy(InputStream is, OutputStream os) throws IOException {
        byte[] buffer = new byte[1024];
        int len;
        while ((len = is.read(buffer)) != -1) {
            os.write(buffer, 0, len);
        }
    }

    /**
     * Parses a string of comma-separated ratings into an array of {@link TvContentRating}.
     *
     * @param commaSeparatedRatings String containing various ratings, separated by commas.
     * @return An array of TvContentRatings.
     * @hide
     */
    public static TvContentRating[] stringToContentRatings(String commaSeparatedRatings) {
        if (TextUtils.isEmpty(commaSeparatedRatings)) {
            return null;
        }
        String[] ratings = commaSeparatedRatings.split("\\s*,\\s*");
        TvContentRating[] contentRatings = new TvContentRating[ratings.length];
        for (int i = 0; i < contentRatings.length; ++i) {
            contentRatings[i] = TvContentRating.unflattenFromString(ratings[i]);
        }
        return contentRatings;
    }

    /**
     * Flattens an array of {@link TvContentRating} into a String to be inserted into a database.
     *
     * @param contentRatings An array of TvContentRatings.
     * @return A comma-separated String of ratings.
     * @hide
     */
    public static String contentRatingsToString(TvContentRating[] contentRatings) {
        if (contentRatings == null || contentRatings.length == 0) {
            return null;
        }
        final String DELIMITER = ",";
        StringBuilder ratings = new StringBuilder(contentRatings[0].flattenToString());
        for (int i = 1; i < contentRatings.length; ++i) {
            ratings.append(DELIMITER);
            ratings.append(contentRatings[i].flattenToString());
        }
        return ratings.toString();
    }

    private TvContractUtils() {
    }

    private static class InsertLogosTask extends AsyncTask<Map<Uri, String>, Void, Void> {
        private final Context mContext;

        InsertLogosTask(Context context) {
            mContext = context;
        }

        @Override
        public Void doInBackground(Map<Uri, String>... logosList) {
            for (Map<Uri, String> logos : logosList) {
                for (Uri uri : logos.keySet()) {
                    try {
                        insertUrl(mContext, uri, new URL(logos.get(uri)));
                    } catch (MalformedURLException e) {
                        Log.e(TAG, "Can't load " + logos.get(uri), e);
                    }
                }
            }
            return null;
        }
    }
}
