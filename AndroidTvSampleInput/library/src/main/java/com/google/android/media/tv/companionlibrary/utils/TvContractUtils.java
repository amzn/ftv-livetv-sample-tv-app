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

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.tv.TvContentRating;
import android.media.tv.TvContract;
import android.media.tv.TvContract.Channels;
import android.net.Uri;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.Pair;
import android.util.SparseArray;

import com.google.android.media.tv.companionlibrary.BaseTvInputService;
import com.google.android.media.tv.companionlibrary.model.Channel;
import com.google.android.media.tv.companionlibrary.model.Program;

import org.json.JSONException;
import org.json.JSONObject;

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

    /** Indicates that no source type has been defined for this video yet */
    public static final int SOURCE_TYPE_INVALID = -1;
    /** Indicates that the video will use MPEG-DASH (Dynamic Adaptive Streaming over HTTP) for
     * playback.
     */
    public static final int SOURCE_TYPE_MPEG_DASH = 0;
    /** Indicates that the video will use SS (Smooth Streaming) for playback. */
    public static final int SOURCE_TYPE_SS = 1;
    /** Indicates that the video will use HLS (HTTP Live Streaming) for playback. */
    public static final int SOURCE_TYPE_HLS = 2;
    /** Indicates that the video will use HTTP Progressive for playback. */
    public static final int SOURCE_TYPE_HTTP_PROGRESSIVE = 3;

    /** The Input ID when the Sample Input Service initialized the service */
    public static final String INPUT_ID = "com.example.android.sampletvinput/.rich.RichTvInputService";

    private static final String TAG = "TvContractUtils";
    private static final boolean DEBUG = false;
    private static final SparseArray<String> VIDEO_HEIGHT_TO_FORMAT_MAP = new SparseArray<>();

    private static final String PACKAGE_NAME = "com.example.android.sampletvinput";
    private static final String DEEP_LINK_ACTIVITY_CLASS_NAME = "com.example.android.sampletvinput.DemoPlayerActivity";

    /**
     * Type of external id, list of types need to be defined as contant in the wiki (Contract of list below)
     * Null or invalid data will result in failed service match of meta data
     */
    private final static String EXTERNAL_ID_TYPE = "externalIdType";

    /**
     * The Id for Gracenote input type
     */
    private final static String GRACENOTE_ID = "gracenote_ontv";

    /**
     * Value of external id, used for matching service meta-data.
     * Null or invalid data will result in failed service match of meta data
     */
    private final static String EXTERNAL_ID_VALUE = "externalIdValue";

    /**
     * Uri for deep link of playback into external player.
     * Null or invalid data will result in default as integrated with Gordon player
     */
    private final static String PLAYBACK_DEEP_LINK_URI = "playbackDeepLinkUri";


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
     * Here is the mock data for Gracenote, if the app uses the Gracenote as the data source.
     * 1. Then the provider don't need to push any program/channel metadata to Tif.
     * 2. But the channel id, package_name and Input Id are still needed for a Gracenote channel
     * 3. The first parameter in the Pair is the Gracenote Id, the the second Parameter of the Pair is the preferred name
     *
     * Refer to the what are being used for a channel in updateChannelsWithGracenote() below
     */
    private static final List<Pair<String, String>> STATIONS = new ArrayList<Pair<String,String>> () {{
        add(new Pair(1001,"MOCK MOVIES"));
        add(new Pair(1002, "MOCK MUSIC"));
        add(new Pair(1003, "MOCK NATION"));
        add(new Pair(1004, "MOCK CHEDDAR"));
        add(new Pair(1005, "MOCK DISCOVERY"));
        add(new Pair(1006, "MOCK SPORT"));
        add(new Pair(1007, "MOCK EUROSPORT 1"));
    }};

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
     * Updates the list of available channels in Tif without DeepLink.
     *
     * @param context The application's context.
     * @param inputId The ID of the TV input service that provides this TV channel.
     * @param channels The updated list of channels. The Provider should be responsible to figure out
     * its own way to collecting those channels. Referring to the method in "getChannels()" in SampleJobService.java
     */
    public static void updateChannelsWithTif(Context context, String inputId, List<Channel> channels) {
        // Create a map from original network ID to channel row ID for existing channels.
        SparseArray<Long> channelMap = new SparseArray<>();
        Uri channelsUri = TvContract.buildChannelsUriForInput(inputId);
        String[] projection = {Channels._ID, Channels.COLUMN_ORIGINAL_NETWORK_ID};
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(channelsUri, projection, null, null, null))
        {
            while (cursor != null && cursor.moveToNext())
            {
                long rowId = cursor.getLong(0);
                int originalNetworkId = cursor.getInt(1);
                channelMap.put(originalNetworkId, rowId);
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to get channels", e);
        }

        // If a channel exists, update it. If not, insert a new one.
        Map<Uri, String> logos = new HashMap<>();
        for (Channel channel : channels) {
            ContentValues values = new ContentValues();
            values.put(Channels.COLUMN_INPUT_ID, inputId);
            values.putAll(channel.toContentValues());
            // If some required fields are not populated, the app may crash, so defaults are used
            if (channel.getPackageName() == null) {
                // If channel does not include package name, it will be added
                values.put(Channels.COLUMN_PACKAGE_NAME, context.getPackageName());
            }
            if (channel.getInputId() == null) {
                // If channel does not include input id, it will be added
                values.put(Channels.COLUMN_INPUT_ID, inputId);
            }
            if (channel.getType() == null) {
                // If channel does not include type it will be added
                values.put(Channels.COLUMN_TYPE, Channels.TYPE_OTHER);
            }

            Long rowId = channelMap.get(channel.getOriginalNetworkId());
            Uri uri;
            if (rowId == null) {
                uri = resolver.insert(TvContract.Channels.CONTENT_URI, values);
                if (DEBUG) {
                    Log.d(TAG, "Adding channel " + channel.getOriginalNetworkId() + " at " + uri);
                }
            } else {
                values.put(Channels._ID, rowId);
                uri = TvContract.buildChannelUri(rowId);
                if (DEBUG) {
                    Log.d(TAG, "Updating channel " + channel.getOriginalNetworkId() + " at " + uri);
                }
                resolver.update(uri, values, null, null);
                channelMap.remove(channel.getOriginalNetworkId());
            }
            if (channel.getChannelLogo() != null && !TextUtils.isEmpty(channel.getChannelLogo())) {
                logos.put(TvContract.buildChannelLogoUri(uri), channel.getChannelLogo());
            }
        }
        if (!logos.isEmpty()) {
            new InsertLogosTask(context).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, logos);
        }

        // Deletes channels which don't exist in the new feed.
        int size = channelMap.size();
        for (int i = 0; i < size; ++i) {
            Long rowId = channelMap.valueAt(i);
            if (DEBUG) {
                Log.d(TAG, "Deleting channel " + rowId);
            }
            resolver.delete(TvContract.buildChannelUri(rowId), null, null);
            SharedPreferences.Editor editor = context.getSharedPreferences(
                    BaseTvInputService.PREFERENCES_FILE_KEY, Context.MODE_PRIVATE).edit();
            editor.remove(BaseTvInputService.SHARED_PREFERENCES_KEY_LAST_CHANNEL_AD_PLAY + rowId);
            editor.apply();
        }
    }

    /**
     * Updates the list of available channels in Tif with DeepLink.
     *
     * @param context The application's context.
     * @param inputId The ID of the TV input service that provides this TV channel.
     * @param channels The updated list of channels.
     * @hide
     */
    public static void updateChannelsWithTifDeepLink(Context context, String inputId, List<Channel> channels) {
        // Create a map from original network ID to channel row ID for existing channels.
        SparseArray<Long> channelMap = new SparseArray<>();
        Uri channelsUri = TvContract.buildChannelsUriForInput(inputId);
        String[] projection = {Channels._ID, Channels.COLUMN_ORIGINAL_NETWORK_ID};
        ContentResolver resolver = context.getContentResolver();

        try (Cursor cursor = resolver.query(channelsUri, projection, null, null, null)) {
            while (cursor != null && cursor.moveToNext())
            {
                long rowId = cursor.getLong(0);
                int originalNetworkId = cursor.getInt(1);
                channelMap.put(originalNetworkId, rowId);
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to get channels", e);
        }

        //Clear the SampleChannelListManager
        SampleChannelListManager.getInstance().clear();


        // If a channel exists, update it. If not, insert a new one.
        Map<Uri, String> logos = new HashMap<>();
        for (Channel channel : channels) {
            //If the provider wants to use the deeplink here, the package name and class name need to be provided
            Intent playbackDeepLinkIntent = new Intent();

            /**
             * Here is just an example that provider can also use deeplink to launch its own Player Activity
             * Please make change accordingly if more action or customized features are needed
             */
            playbackDeepLinkIntent.setComponent(new ComponentName(PACKAGE_NAME,DEEP_LINK_ACTIVITY_CLASS_NAME));
            SampleChannelListManager channelListManager = SampleChannelListManager.getInstance();

            ContentValues values = new ContentValues();
            values.putAll(channel.toContentValues());
            // If some required fields are not populated, the app may crash, so defaults are used
            if (channel.getPackageName() == null) {
                // If channel does not include package name, it will be added
                values.put(Channels.COLUMN_PACKAGE_NAME, context.getPackageName());
            }
            if (channel.getInputId() == null) {
                // If channel does not include input id, it will be added
                values.put(Channels.COLUMN_INPUT_ID, inputId);
            }
            if (channel.getType() == null) {
                // If channel does not include type it will be added
                values.put(Channels.COLUMN_TYPE, Channels.TYPE_OTHER);
            }

            playbackDeepLinkIntent.putExtra(CHANNEL_DEEP_LINK_INTENT_PRIM_KEY,channel.getOriginalNetworkId());
            playbackDeepLinkIntent.putExtra(CHANNEL_DEEP_LINK_INTENT_SEC_KEY,channel.getInputId());

            Log.i(TAG, "originalNetworkId is: " + channel.getOriginalNetworkId());

            Long rowId = channelMap.get(channel.getOriginalNetworkId());

            /** This part in mandatory for launching a deep link */
            try {
                String jsonString = new JSONObject()
                        .put(PLAYBACK_DEEP_LINK_URI, playbackDeepLinkIntent.toUri(Intent.URI_INTENT_SCHEME))
                        .toString();

                values.put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA, jsonString.getBytes());
            } catch (JSONException e) {
                Log.i(TAG, "Error when adding data to blob " + e);
            }

            Uri uri;
            if (rowId == null) {
                uri = resolver.insert(TvContract.Channels.CONTENT_URI, values);
                Log.i(TAG, "rowId == null & Adding channel " + channel.getOriginalNetworkId() + " at " + uri.toString());
                channelListManager.addChannelUri(channel.getOriginalNetworkId(),uri);
            } else {
                values.put(Channels._ID, rowId);
                uri = TvContract.buildChannelUri(rowId);
                resolver.update(uri, values, null, null);
                Log.i(TAG, " rowId != null & Updating channel " + channel.getOriginalNetworkId() + " at " + uri);
                channelListManager.addChannelUri(channel.getOriginalNetworkId(),uri);
                channelMap.remove(channel.getOriginalNetworkId());
            }


            if (channel.getChannelLogo() != null && !TextUtils.isEmpty(channel.getChannelLogo())) {
                logos.put(TvContract.buildChannelLogoUri(uri), channel.getChannelLogo());
            }
        }
        if (!logos.isEmpty()) {
            new InsertLogosTask(context).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, logos);
        }

        /** You need to un-comment the following logic when you decide to use the deep-link solution
         *  The reason why it is comment here is to demo both Tif Integration and Deep_Link Integration */
        // Deletes channels which don't exist in the new feed.
//        int size = channelMap.size();
//        for (int i = 0; i < size; ++i) {
//            Long rowId = channelMap.valueAt(i);
//            if (DEBUG) {
//                Log.d(TAG, "Deleting channel " + rowId);
//            }
//            resolver.delete(TvContract.buildChannelUri(rowId), null, null);
//            SharedPreferences.Editor editor = context.getSharedPreferences(
//                    BaseTvInputService.PREFERENCES_FILE_KEY, Context.MODE_PRIVATE).edit();
//            editor.remove(BaseTvInputService.SHARED_PREFERENCES_KEY_LAST_CHANNEL_AD_PLAY + rowId);
//            editor.apply();
//        }
    }

    /**
     * Updates the list of available channels.
     *
     * @param context The application's context.
     * @param inputId The ID of the TV input service that provides this TV channel.
     */
    public static void updateChannelsWithGraceNote(Context context, String inputId) {
        // Create a map from original network ID to channel row ID for existing channels.
        Uri channelsUri = TvContract.buildChannelsUriForInput(inputId);
        ContentResolver resolver = context.getContentResolver();
        Cursor cursor = null;
        try {
            cursor = resolver.query(channelsUri, new String[] {TvContract.Channels._ID}, null, null, null);
            if (cursor != null && cursor.getCount() == STATIONS.size()) {
                Log.d(TAG, "skipping update due to same station list size");
                return;
            } else {
                int numDeleted = resolver.delete(channelsUri, null, null);
                Log.d(TAG, "deleted " + numDeleted + " channels");
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        Intent playbackDeepLinkIntent = new Intent();
        playbackDeepLinkIntent.setComponent(new ComponentName("com.foo","com.foo.MissingActivity"));

        for (Pair<String,String> station : STATIONS) {
            ContentValues values = new ContentValues();
            values.put(Channels.COLUMN_INPUT_ID, inputId);

            values.put(Channels.COLUMN_PACKAGE_NAME, context.getPackageName());
            values.put(Channels.COLUMN_DISPLAY_NAME, station.second);
            values.put(Channels.COLUMN_INPUT_ID, inputId);
            values.put(Channels.COLUMN_TYPE, Channels.TYPE_OTHER);

            try {
                String jsonString = new JSONObject()
                        .put(EXTERNAL_ID_TYPE, GRACENOTE_ID)
                        .put(EXTERNAL_ID_VALUE, station.first)
                        .put(PLAYBACK_DEEP_LINK_URI, playbackDeepLinkIntent.toUri(Intent.URI_INTENT_SCHEME))
                        .toString();

                values.put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA, jsonString.getBytes());
            } catch (JSONException e) {
                Log.e(TAG, "Error when adding data to blob " + e);
            }

            Uri uri = resolver.insert(TvContract.Channels.CONTENT_URI, values);
            if (DEBUG) {
                Log.d(TAG, "Adding channel " + station.second + " at " + uri);
            }
        }
    }

    /**
     * Builds a map of available channels.
     *
     * @param resolver Application's ContentResolver.
     * @param inputId The ID of the TV input service that provides this TV channel.
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
     * Returns the current list of channels your app provides.
     *
     * @param resolver Application's ContentResolver.
     * @return List of channels.
     */
    public static List<Channel> getChannels(ContentResolver resolver) {
        List<Channel> channels = new ArrayList<>();
        // TvProvider returns programs in chronological order by default.
        Cursor cursor = null;
        try {
            cursor = resolver.query(Channels.CONTENT_URI, Channel.PROJECTION, null, null, null);
            if (cursor == null || cursor.getCount() == 0) {
                return channels;
            }
            while (cursor.moveToNext()) {
                channels.add(Channel.fromCursor(cursor));
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to get channels", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return channels;
    }

    /**
     * Returns the {@link Channel} with specified channel URI.
     * @param resolver {@link ContentResolver} used to query database.
     * @param channelUri URI of channel.
     * @return An channel object with specified channel URI.
     * @hide
     */
    public static Channel getChannel(ContentResolver resolver, Uri channelUri) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(channelUri, Channel.PROJECTION, null, null, null);
            if (cursor == null || cursor.getCount() == 0) {
                Log.w(TAG, "No channel matches " + channelUri);
                return null;
            }
            cursor.moveToNext();
            return Channel.fromCursor(cursor);
        } catch (Exception e) {
            Log.w(TAG, "Unable to get the channel with URI " + channelUri, e);
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();

            }
        }
    }

    /**
     * Returns the current list of programs on a given channel.
     *
     * @param resolver Application's ContentResolver.
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
     * @param resolver Application's ContentResolver.
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
     * @param resolver Application's ContentResolver.
     * @param channelUri Channel's Uri.
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
