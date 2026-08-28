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
package com.example.android.sampletvinput;

import android.content.ComponentName;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.tv.TvContentRating;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.RemoteException;
import android.util.Log;

import com.example.android.sampletvinput.rich.RichFeedUtil;

import androidx.tvprovider.media.tv.ChannelLogoUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * SampleChannelManager handles channel and program insertion into the TIF database.
 *
 * There are 13 channels defined here:
 * - Channels 1-2: From XML feed, deep link playback (DemoPlayerActivity)
 * - Channel 3: From XML feed, native playback (MPEG-DASH)
 * - Channel 4: Programmatic, native playback (MPEG-DASH, Tears of Steel)
 * - Channels 5-8: Gracenote external metadata, native playback (no local programs needed)
 * - Channels 9-13: Programmatic, native playback (MPEG-DASH, Tears of Steel, Sports genre)
 *
 * A real app would pull this list from a live catalog, feed, or API rather than
 * hardcoding it — these 13 exist purely to demonstrate several integration patterns.
 *
 * See: https://developer.amazon.com/docs/fire-tv/insert-your-first-channel.html
 */
public class SampleChannelManager {
    private static final String TAG = "SampleChannelManager";

    private static final String TEARS_OF_STEEL_DESCRIPTION =
            "Monsters invade a small town in this sci-fi flick";

    /**
     * Deep link intent constants.
     * CHANNEL_DEEP_LINK_INTENT_PRIM_KEY: channel's originalNetworkId
     * CHANNEL_DEEP_LINK_INTENT_SEC_KEY: channel's inputId
     */
    public static final String CHANNEL_DEEP_LINK_INTENT_PRIM_KEY = "channel_deep_link_intent_prim_key";
    public static final String CHANNEL_DEEP_LINK_INTENT_SEC_KEY = "channel_deep_link_intent_sec_key";

    private static final String DEEP_LINK_ACTIVITY_CLASS_NAME =
            "com.example.android.sampletvinput.DemoPlayerActivity";
    private static final String PACKAGE_NAME = "com.example.android.sampletvinput";
    private static final String RICH_INPUT_SERVICE_CLASS =
            "com.example.android.sampletvinput.rich.RichTvInputService";
    private static final String INPUT_ID = PACKAGE_NAME + "/.rich.RichTvInputService";

    /**
     * A station's ID type for Gracenote metadata.
     */
    private static final String GRACENOTE_ID = "gracenote_ontv";

    /** Denotes channels intended to be used for the deeplink integration. */
    private static final String DEEPLINK_SUFFIX = "-DeepLink";

    // Keys for INTERNAL_PROVIDER_DATA JSON blob (read by Fire TV)
    // Channel-level keys: externalIdType, externalIdValue, playbackDeepLinkUri
    // Program-level keys (used in this sample): videoUrl, videoType
    private static final String KEY_EXTERNAL_ID_TYPE = "externalIdType";
    private static final String KEY_EXTERNAL_ID_VALUE = "externalIdValue";
    private static final String KEY_PLAYBACK_DEEP_LINK_URI = "playbackDeepLinkUri";
    private static final String KEY_VIDEO_TYPE = "videoType";
    private static final String KEY_VIDEO_URL = "videoUrl";

    public static final int SOURCE_TYPE_HTTP_PROGRESSIVE = 0;
    public static final int SOURCE_TYPE_HLS = 1;
    public static final int SOURCE_TYPE_MPEG_DASH = 2;

    // SharedPreferences map: originalNetworkId → TIF _id. Used by DemoPlayerActivity to
    // resolve a deep-linked channel's TIF _id without scanning every channel.
    public static final String PREFS_CHANNEL_MAP = "channel_id_map";

    // Amazon TifExtension content provider (used for genre data in this sample)
    private static final Uri TIF_EXTENSION_URI =
            Uri.parse("content://com.amazon.tv.livetv.tifextension/channel");
    private static final String TIF_EXT_COLUMN_INPUT_ID = "input_id";
    private static final String TIF_EXT_COLUMN_CHANNEL_ID = "channel_id";
    private static final String TIF_EXT_COLUMN_GENRE = "genre";

    // Columns queried to detect existing channels and diff their metadata.
    private static final String[] EXISTING_CHANNEL_PROJECTION = {
            TvContract.Channels._ID,
            TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID,
            TvContract.Channels.COLUMN_DISPLAY_NAME,
            TvContract.Channels.COLUMN_DISPLAY_NUMBER,
            TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA,
    };

    /**
     * Describes one channel to sync: its ContentValues, plus post-batch work to run once
     * its TIF row _id is known (logo, genre tag, programs).
     *
     * Exists because this sample hardcodes several channel types side by side to show
     * different integration patterns. A production app usually has one channel data
     * source (a catalog API, a feed), so it likely wouldn't need this per-type spec.
     */
    private static class ChannelSpec {
        final int originalNetworkId;
        final ContentValues values;
        final String logoUrl;
        final String genre;
        final ProgramInserter programInserter;

        ChannelSpec(int originalNetworkId, ContentValues values, String logoUrl, String genre,
                    ProgramInserter programInserter) {
            this.originalNetworkId = originalNetworkId;
            this.values = values;
            this.logoUrl = logoUrl;
            this.genre = genre;
            this.programInserter = programInserter;
        }
    }

    private interface ProgramInserter {
        boolean insertPrograms(ContentResolver resolver, long channelId);
    }

    /**
     * Performs a channel and program sync: queries existing channels, then inserts,
     * updates, or deletes only what changed. Unchanged channels keep the same TIF row
     * _id. If the channel query fails (returns null), deletes all and reinserts from scratch.
     *
     * synchronized: called from several places (both receivers, the periodic job, setup
     * activity, playback's resync path) — prevents two of them racing on the same data.
     *
     * See: https://developer.amazon.com/docs/fire-tv/live-tv-resources.html
     *
     * @param context Application context
     * @return true if the sync completed fully; false if a channel batch operation
     *         failed, a desired channel was skipped, or program insertion failed
     */
    public static synchronized boolean syncChannels(Context context) {
        String inputId = TvContract.buildInputId(
                new ComponentName(PACKAGE_NAME, RICH_INPUT_SERVICE_CLASS));
        Log.d(TAG, "syncChannels: inputId=" + inputId);

        ContentResolver resolver = context.getContentResolver();
        Uri channelsUri = TvContract.buildChannelsUriForInput(inputId);

        // Step 1: Read what's already in the TIF database for this input, keyed by
        // originalNetworkId, so we can compare it against what we want below.
        Map<Integer, Long> existingChannelIds = new HashMap<>();
        Map<Integer, ContentValues> existingChannelValues = new HashMap<>();
        try (Cursor cursor = resolver.query(channelsUri, EXISTING_CHANNEL_PROJECTION,
                null, null, null)) {
            if (cursor == null) {
                // Null means the query failed (e.g. TIF provider unavailable) — different
                // from an empty cursor, which means "no channels yet." Since we can't diff,
                // delete all channels for this input and reinsert from scratch.
                Log.w(TAG, "Channel query returned null; deleting all channels and reinserting");
                resolver.delete(channelsUri, null, null);
            } else {
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(cursor.getColumnIndexOrThrow(TvContract.Channels._ID));
                    int networkId = cursor.getInt(cursor.getColumnIndexOrThrow(
                            TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID));
                    ContentValues existing = new ContentValues();
                    existing.put(TvContract.Channels.COLUMN_DISPLAY_NAME, cursor.getString(
                            cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_DISPLAY_NAME)));
                    existing.put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, cursor.getString(
                            cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_DISPLAY_NUMBER)));
                    existing.put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA, cursor.getBlob(
                            cursor.getColumnIndexOrThrow(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA)));
                    existingChannelIds.put(networkId, id);
                    existingChannelValues.put(networkId, existing);
                }
            }
        }

        // Step 2: Build the full list of channels we want, independent of what's currently
        // in the database. This is the source of truth we diff against in step 3.
        List<ChannelSpec> desiredChannels = buildDesiredChannels(context, inputId);

        // Step 3: Insert missing channels, update changed ones, leave the rest alone, and
        // delete channels no longer in the lineup.
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        Set<Integer> desiredNetworkIds = new HashSet<>();
        Set<Integer> changedNetworkIds = new HashSet<>();

        for (ChannelSpec spec : desiredChannels) {
            desiredNetworkIds.add(spec.originalNetworkId);
            Long existingId = existingChannelIds.get(spec.originalNetworkId);
            if (existingId == null) {
                ops.add(ContentProviderOperation.newInsert(TvContract.Channels.CONTENT_URI)
                        .withValues(spec.values)
                        .build());
                changedNetworkIds.add(spec.originalNetworkId);
            } else if (!channelMetadataMatches(existingChannelValues.get(spec.originalNetworkId), spec.values)) {
                ops.add(ContentProviderOperation.newUpdate(TvContract.buildChannelUri(existingId))
                        .withValues(spec.values)
                        .build());
                changedNetworkIds.add(spec.originalNetworkId);
            }
            // else: metadata unchanged — no operation needed for this channel row.
        }

        for (Map.Entry<Integer, Long> entry : existingChannelIds.entrySet()) {
            if (!desiredNetworkIds.contains(entry.getKey())) {
                ops.add(ContentProviderOperation.newDelete(
                        TvContract.buildChannelUri(entry.getValue())).build());
            }
        }

        // Step 4: Apply all channel inserts/updates/deletes as a single atomic batch.
        if (!ops.isEmpty()) {
            try {
                resolver.applyBatch(TvContract.AUTHORITY, ops);
            } catch (RemoteException | OperationApplicationException e) {
                Log.e(TAG, "Error applying channel batch", e);
                return false;
            }
        }
        Log.d(TAG, "Channel batch applied: " + ops.size() + " operation(s), "
                + changedNetworkIds.size() + " channel(s) changed");

        // Step 5: Re-query for each channel's TIF _id now that the batch has committed,
        // rather than relying on applyBatch()'s insert results.
        Map<Integer, Long> channelIdsByNetworkId = new HashMap<>();
        try (Cursor cursor = resolver.query(channelsUri, EXISTING_CHANNEL_PROJECTION,
                null, null, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(cursor.getColumnIndexOrThrow(TvContract.Channels._ID));
                    int networkId = cursor.getInt(cursor.getColumnIndexOrThrow(
                            TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID));
                    channelIdsByNetworkId.put(networkId, id);
                }
            }
        }

        // Step 6: Rebuild the originalNetworkId -> TIF _id lookup that DemoPlayerActivity
        // uses for deep links, and run any per-channel follow-up work (logo, genre tag,
        // programs) for channels that actually changed.
        boolean syncFullyCompleted = true;
        SharedPreferences.Editor mapEditor = context.getSharedPreferences(
                PREFS_CHANNEL_MAP, Context.MODE_PRIVATE).edit();
        mapEditor.clear();

        for (ChannelSpec spec : desiredChannels) {
            Long channelId = channelIdsByNetworkId.get(spec.originalNetworkId);
            if (channelId == null) {
                Log.w(TAG, "No channel id for network id " + spec.originalNetworkId + "; skipping");
                syncFullyCompleted = false;
                continue;
            }
            mapEditor.putLong(String.valueOf(spec.originalNetworkId), channelId);

            if (changedNetworkIds.contains(spec.originalNetworkId)) {
                if (spec.logoUrl != null) {
                    ChannelLogoUtils.storeChannelLogo(context, channelId, Uri.parse(spec.logoUrl));
                }
                if (spec.genre != null) {
                    try {
                        ContentValues genreValues = new ContentValues();
                        genreValues.put(TIF_EXT_COLUMN_INPUT_ID, inputId);
                        genreValues.put(TIF_EXT_COLUMN_CHANNEL_ID, channelId);
                        genreValues.put(TIF_EXT_COLUMN_GENRE, spec.genre);
                        resolver.insert(TIF_EXTENSION_URI, genreValues);
                    } catch (Exception e) {
                        Log.w(TAG, "Could not insert genre data for network id "
                                + spec.originalNetworkId + ": " + e.getMessage());
                    }
                }
            }

            if (spec.programInserter != null) {
                // For this demo app, programs are regenerated every sync, even for unchanged
                // channels, because their schedule is always built starting from "now", so it goes
                // stale otherwise.
                resolver.delete(TvContract.buildProgramsUriForChannel(channelId), null, null);
                if (!spec.programInserter.insertPrograms(resolver, channelId)) {
                    Log.e(TAG, "Failed to insert programs for channel " + channelId);
                    syncFullyCompleted = false;
                }
            }
        }

        // Step 7: Save the routing map.
        mapEditor.commit();
        Log.d(TAG, "Channel sync complete");
        return syncFullyCompleted;
    }

    /**
     * Returns true if the existing channel's diffable metadata matches what we'd insert.
     */
    private static boolean channelMetadataMatches(ContentValues existing, ContentValues desired) {
        if (!Objects.equals(existing.getAsString(TvContract.Channels.COLUMN_DISPLAY_NAME),
                desired.getAsString(TvContract.Channels.COLUMN_DISPLAY_NAME))) {
            return false;
        }
        if (!Objects.equals(existing.getAsString(TvContract.Channels.COLUMN_DISPLAY_NUMBER),
                desired.getAsString(TvContract.Channels.COLUMN_DISPLAY_NUMBER))) {
            return false;
        }
        byte[] existingData = existing.getAsByteArray(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA);
        byte[] desiredData = desired.getAsByteArray(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA);
        return Arrays.equals(existingData, desiredData);
    }

    /**
     * Builds the full set of desired channels (source of truth for this sync) as specs,
     * ready to be diffed against what's already in the TIF database.
     */
    private static List<ChannelSpec> buildDesiredChannels(Context context, String inputId) {
        List<ChannelSpec> specs = new ArrayList<>();

        for (RichFeedUtil.ChannelInfo channelInfo : RichFeedUtil.getChannels(context)) {
            specs.add(buildXmlChannelSpec(context, inputId, channelInfo));
        }

        specs.add(buildMpegDashChannelSpec(inputId));
        specs.addAll(buildGracenoteChannelSpecs(inputId));
        specs.addAll(buildGenreChannelSpecs(inputId));

        return specs;
    }

    /**
     * Builds the spec for an XML-feed channel (channels 1-3). Deep-link channels get a
     * playbackDeepLinkUri in INTERNAL_PROVIDER_DATA.
     */
    private static ChannelSpec buildXmlChannelSpec(Context context, String inputId,
                                                    RichFeedUtil.ChannelInfo channelInfo) {
        ContentValues values = new ContentValues();
        values.put(TvContract.Channels.COLUMN_INPUT_ID, inputId);
        values.put(TvContract.Channels.COLUMN_DISPLAY_NAME, channelInfo.displayName);
        values.put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, channelInfo.displayNumber);
        values.put(TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID, channelInfo.originalNetworkId);

        if (channelInfo.displayName.endsWith(DEEPLINK_SUFFIX)) {
            Intent playbackDeepLinkIntent = new Intent();
            playbackDeepLinkIntent.setComponent(
                    new ComponentName(PACKAGE_NAME, DEEP_LINK_ACTIVITY_CLASS_NAME));
            playbackDeepLinkIntent.putExtra(CHANNEL_DEEP_LINK_INTENT_PRIM_KEY,
                    channelInfo.originalNetworkId);
            playbackDeepLinkIntent.putExtra(CHANNEL_DEEP_LINK_INTENT_SEC_KEY, INPUT_ID);

            try {
                JSONObject json = new JSONObject();
                json.put(KEY_PLAYBACK_DEEP_LINK_URI,
                        playbackDeepLinkIntent.toUri(Intent.URI_INTENT_SCHEME));
                values.put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA,
                        json.toString().getBytes());
            } catch (JSONException e) {
                Log.e(TAG, "Error creating deep link JSON", e);
            }
        }

        ProgramInserter programInserter = (resolver, channelId) ->
                insertProgramsForXmlChannel(context, channelId, channelInfo);

        return new ChannelSpec(channelInfo.originalNetworkId, values, channelInfo.logoUrl, null,
                programInserter);
    }

    /**
     * Inserts programs for an XML-based channel.
     * Programs are repeated in a loop to fill a 24-hour schedule window so that a
     * program is always currently airing for this demo app.
     */
    private static boolean insertProgramsForXmlChannel(Context context, long channelId,
                                                        RichFeedUtil.ChannelInfo channelInfo) {
        List<RichFeedUtil.ProgramInfo> programs = RichFeedUtil.getPrograms(context, channelInfo.id);
        if (programs == null || programs.isEmpty()) return true;

        ContentResolver resolver = context.getContentResolver();
        long now = System.currentTimeMillis();

        // Calculate total duration of one cycle of programs
        long cycleDurationMs = 0;
        for (RichFeedUtil.ProgramInfo program : programs) {
            long programDuration = program.endOffsetMs - program.startOffsetMs;
            cycleDurationMs += programDuration;
        }
        if (cycleDurationMs <= 0) return true;

        // Fill 24 hours of schedule by repeating programs
        long scheduleWindowMs = 24 * 60 * 60 * 1000;
        long scheduleStart = now - (60 * 60 * 1000); // Start 1 hour in past so a program is always "currently airing"
        long scheduleEnd = now + scheduleWindowMs;
        long currentStart = scheduleStart;

        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        while (currentStart < scheduleEnd) {
            for (RichFeedUtil.ProgramInfo program : programs) {
                long programDuration = program.endOffsetMs - program.startOffsetMs;
                long startTime = currentStart;
                long endTime = currentStart + programDuration;

                if (startTime >= scheduleEnd) break;

                ContentValues values = new ContentValues();
                values.put(TvContract.Programs.COLUMN_CHANNEL_ID, channelId);
                values.put(TvContract.Programs.COLUMN_TITLE, program.title);
                values.put(TvContract.Programs.COLUMN_SHORT_DESCRIPTION, program.description);
                values.put(TvContract.Programs.COLUMN_LONG_DESCRIPTION, program.longDescription);
                values.put(TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, startTime);
                values.put(TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS, endTime);
                values.put(TvContract.Programs.COLUMN_POSTER_ART_URI, program.posterArtUri);
                values.put(TvContract.Programs.COLUMN_THUMBNAIL_URI, program.thumbnailUri);

                if (program.contentRating != null) {
                    values.put(TvContract.Programs.COLUMN_CONTENT_RATING, program.contentRating);
                }

                try {
                    JSONObject json = new JSONObject();
                    json.put(KEY_VIDEO_URL, program.videoUrl);
                    json.put(KEY_VIDEO_TYPE, program.videoType);
                    values.put(TvContract.Programs.COLUMN_INTERNAL_PROVIDER_DATA,
                            json.toString().getBytes());
                } catch (JSONException e) {
                    Log.e(TAG, "Error creating program JSON", e);
                }

                ops.add(ContentProviderOperation.newInsert(TvContract.Programs.CONTENT_URI)
                        .withValues(values)
                        .build());
                currentStart = endTime;
            }
        }
        if (!applyProgramBatch(resolver, ops)) return false;
        Log.d(TAG, "Inserted repeating programs for channel " + channelId);
        return true;
    }

    /**
     * Builds the spec for the MPEG-DASH channel (channel 4) with a Tears of Steel program.
     */
    private static ChannelSpec buildMpegDashChannelSpec(String inputId) {
        int originalNetworkId = 101;

        ContentValues values = new ContentValues();
        values.put(TvContract.Channels.COLUMN_INPUT_ID, inputId);
        values.put(TvContract.Channels.COLUMN_DISPLAY_NAME, "MPEG-Amz Player");
        values.put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, "4");
        values.put(TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID, originalNetworkId);

        String logoUrl = "https://storage.googleapis.com/android-tv/images/mpeg_dash.png";
        String programUrl = "https://ecx.images-amazon.com/images/I/61aoo6-ulML.png";
        ProgramInserter programInserter = (resolver, channelId) ->
                insertTearsOfSteelProgram(resolver, channelId, TEARS_OF_STEEL_DESCRIPTION, programUrl);

        return new ChannelSpec(originalNetworkId, values, logoUrl, null, programInserter);
    }

    /**
     * Builds specs for Gracenote channels (channels 5-8).
     * These channels get external metadata from Gracenote — no local programs needed.
     * Only the externalIdType and externalIdValue are inserted.
     *
     * The IDs below are samples, not real catalog entries — a production app would use
     * its actual onboarded Gracenote IDs.
     */
    private static List<ChannelSpec> buildGracenoteChannelSpecs(String inputId) {
        List<String> gracenoteIds = Arrays.asList("10051", "10057", "10138", "58780");
        List<ChannelSpec> specs = new ArrayList<>();
        int channelNum = 5;

        for (String id : gracenoteIds) {
            ContentValues values = new ContentValues();
            values.put(TvContract.Channels.COLUMN_INPUT_ID, inputId);
            values.put(TvContract.Channels.COLUMN_DISPLAY_NAME, "Channel " + id);
            values.put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, String.valueOf(channelNum++));
            values.put(TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID, Integer.parseInt(id));

            try {
                JSONObject json = new JSONObject();
                json.put(KEY_EXTERNAL_ID_TYPE, GRACENOTE_ID);
                json.put(KEY_EXTERNAL_ID_VALUE, id);
                values.put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA,
                        json.toString().getBytes());
            } catch (JSONException e) {
                Log.e(TAG, "Error creating Gracenote JSON", e);
            }

            specs.add(new ChannelSpec(Integer.parseInt(id), values, null, null, null));
        }
        return specs;
    }

    /**
     * Builds specs for genre-tagged channels (channels 9-13).
     * Genre attribution is a (Recommended) item in Fire TV's certification checklist —
     * it lets a channel show up in additional ingress points in the Fire TV UI.
     *
     * See: https://developer.amazon.com/docs/fire-tv/tv-input-framework-on-fire-tv.html
     */
    private static List<ChannelSpec> buildGenreChannelSpecs(String inputId) {
        List<String> channelNames = Arrays.asList(
                "Genre Channel 1", "Genre Channel 2", "Genre Channel 3",
                "Genre Channel 4", "Genre Channel 5");
        String logoUrl = "https://ecx.images-amazon.com/images/I/21tj+38IfML.png";
        String programUrl = "https://ecx.images-amazon.com/images/I/61TnDMKesdL.png";

        List<ChannelSpec> specs = new ArrayList<>();
        int channelNum = 9;
        int networkId = 110;

        for (String name : channelNames) {
            int currentNetworkId = networkId++;

            ContentValues values = new ContentValues();
            values.put(TvContract.Channels.COLUMN_INPUT_ID, inputId);
            values.put(TvContract.Channels.COLUMN_DISPLAY_NAME, name);
            values.put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, String.valueOf(channelNum++));
            values.put(TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID, currentNetworkId);

            ProgramInserter programInserter = (resolver, channelId) ->
                    insertTearsOfSteelProgram(resolver, channelId,
                            "Program for genre tagged channel: " + TEARS_OF_STEEL_DESCRIPTION,
                            programUrl);

            specs.add(new ChannelSpec(currentNetworkId, values, logoUrl, "Sports", programInserter));
        }
        return specs;
    }

    /**
     * Inserts Tears of Steel programs for a given channel, repeating to fill 24 hours.
     */
    private static boolean insertTearsOfSteelProgram(ContentResolver resolver, long channelId,
                                                      String description, String thumbnailUri) {
        long durationMs = 734 * 1000;

        long now = System.currentTimeMillis();
        long scheduleEnd = now + (24 * 60 * 60 * 1000);
        long currentStart = now - (60 * 60 * 1000); // Start 1 hour in past so a program is always "currently airing"

        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        while (currentStart < scheduleEnd) {
            ContentValues values = new ContentValues();
            values.put(TvContract.Programs.COLUMN_CHANNEL_ID, channelId);
            values.put(TvContract.Programs.COLUMN_TITLE, "Tears of Steel");
            values.put(TvContract.Programs.COLUMN_SHORT_DESCRIPTION, description);
            values.put(TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, currentStart);
            values.put(TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS, currentStart + durationMs);
            values.put(TvContract.Programs.COLUMN_POSTER_ART_URI,
                    "https://storage.googleapis.com/gtv-videos-bucket/sample/images/tears.jpg");
            values.put(TvContract.Programs.COLUMN_THUMBNAIL_URI, thumbnailUri);
            values.put(TvContract.Programs.COLUMN_CONTENT_RATING,
                    TvContentRating.createRating("com.android.tv", "US_TV", "US_TV_PG")
                            .flattenToString());
            values.put(TvContract.Programs.COLUMN_CANONICAL_GENRE,
                    TvContract.Programs.Genres.encode(TvContract.Programs.Genres.TECH_SCIENCE,
                            TvContract.Programs.Genres.MOVIES));

            try {
                JSONObject json = new JSONObject();
                json.put(KEY_VIDEO_URL, "https://storage.googleapis.com/wvmedia/clear/h264/tears/tears.mpd");
                json.put(KEY_VIDEO_TYPE, SOURCE_TYPE_MPEG_DASH);
                values.put(TvContract.Programs.COLUMN_INTERNAL_PROVIDER_DATA,
                        json.toString().getBytes());
            } catch (JSONException e) {
                Log.e(TAG, "Error creating program JSON", e);
            }

            ops.add(ContentProviderOperation.newInsert(TvContract.Programs.CONTENT_URI)
                    .withValues(values)
                    .build());
            currentStart += durationMs;
        }
        if (!applyProgramBatch(resolver, ops)) return false;
        Log.d(TAG, "Inserted repeating programs for channel " + channelId);
        return true;
    }

    // Caps the number of operations applied in a single batch.
    private static final int BATCH_OPERATION_COUNT = 100;

    /**
     * Applies a batch of program insert operations, chunked to avoid an oversized batch in
     * a single applyBatch() call. Returns false if any chunk fails.
     */
    private static boolean applyProgramBatch(ContentResolver resolver,
                                              ArrayList<ContentProviderOperation> ops) {
        for (int start = 0; start < ops.size(); start += BATCH_OPERATION_COUNT) {
            List<ContentProviderOperation> chunk = ops.subList(
                    start, Math.min(start + BATCH_OPERATION_COUNT, ops.size()));
            try {
                resolver.applyBatch(TvContract.AUTHORITY, new ArrayList<>(chunk));
            } catch (RemoteException | OperationApplicationException e) {
                Log.e(TAG, "Error applying program batch", e);
                return false;
            }
        }
        return true;
    }
}
