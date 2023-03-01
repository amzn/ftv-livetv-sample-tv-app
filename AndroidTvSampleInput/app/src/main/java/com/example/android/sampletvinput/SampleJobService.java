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
import android.content.Intent;
import android.media.tv.TvContentRating;
import android.media.tv.TvContract;
import android.net.Uri;
import com.example.android.sampletvinput.rich.RichFeedUtil;
import com.google.android.exoplayer.util.Util;
import com.google.android.media.tv.companionlibrary.EpgSyncJobService;
import com.google.android.media.tv.companionlibrary.XmlTvParser;
import com.google.android.media.tv.companionlibrary.model.Channel;
import com.google.android.media.tv.companionlibrary.model.InternalProviderData;
import com.google.android.media.tv.companionlibrary.model.Program;
import com.google.android.media.tv.companionlibrary.model.TifExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.google.android.media.tv.companionlibrary.XmlTvParser.ANDROID_TV_RATING;

/**
 * EpgSyncJobService that periodically runs to update channels and programs.
 */
public class SampleJobService extends EpgSyncJobService {

    /**
     * Channel and Program data to be used for creating inline channel and program data
     */
    private String MPEG_DASH_CHANNEL_NAME = "MPEG-Amz Player";
    private String MPEG_DASH_CHANNEL_NUMBER = "4";
    private String MPEG_DASH_CHANNEL_LOGO
            = "https://storage.googleapis.com/android-tv/images/mpeg_dash.png";
    private String MPEG_DASH_PROGRAM_URL
            = "http://ecx.images-amazon.com/images/I/61aoo6-ulML.png";
    private int MPEG_DASH_ORIGINAL_NETWORK_ID = 101;

    private static final String SPORTS_GENRE = "Sports";
    private List<String> GENRE_CHANNEL_NAMES = Arrays.asList(
            "Genre Channel 1",
            "Genre Channel 2",
            "Genre Channel 3",
            "Genre Channel 4",
            "Genre Channel 5");

    private String GENRE_CHANNEL_LOGO
            = "http://ecx.images-amazon.com/images/I/21tj+38IfML.png";
    private String GENRE_PROGRAM_URL
            = "http://ecx.images-amazon.com/images/I/61TnDMKesdL.png";
    private String TEARS_OF_STEEL_TITLE = "Tears of Steel";
    private String TEARS_OF_STEEL_DESCRIPTION = "Monsters invade a small town in this sci-fi flick";
    private String TEARS_OF_STEEL_ART
            = "https://storage.googleapis.com/gtv-videos-bucket/sample/images/tears.jpg";
    private String TEARS_OF_STEEL_SOURCE
            = "https://storage.googleapis.com/wvmedia/clear/h264/tears/tears.mpd";
    private static final long TEARS_OF_STEEL_START_TIME_MS = 0;
    private static final long TEARS_OF_STEEL_DURATION_MS = 734 * 1000;

    /**
     * This constant is used for deeplink intents
     * This is the primary constant that is used to get the Channel Information from the intent
     * In here, the key-value pair in the intent is "channel_key" and channel's originalNetworkId
     */
    public static final String CHANNEL_DEEP_LINK_INTENT_PRIM_KEY = "channel_deep_link_intent_prim_key";

    /**
     * This constant is used for deeplink intents
     * This the secondary constant that is to get the Channel Input information from the intent
     * In here, the key-value pair in the intent is "channel_key" and channel's inputId
     */
    public static final String CHANNEL_DEEP_LINK_INTENT_SEC_KEY = "channel_deep_link_intent_sec_key";

    /**
     * This constant represents the activity to start upon deeplinking
     */
    private static final String DEEP_LINK_ACTIVITY_CLASS_NAME = "com.example.android.sampletvinput.DemoPlayerActivity";


    private static final String PACKAGE_NAME = "com.example.android.sampletvinput";
    private static final String INPUT_ID = PACKAGE_NAME + "/.rich.RichTvInputService";

    /**
     * A station's ID type is used to determine where station metadata will be retrieved from.
     * If the station metadata provider is Gracenote, use the corresponding Gracenote id type.
     * If your organization integrates directly with Amazon's catalog to supply station metadata,
     * use the ID type defined during catalog integration.
     *
     * Refer to the Live TV integration docs for all valid values.
     */
    private final static String GRACENOTE_ID = "gracenote_ontv";

    /**
     * Denotes channels intended to be used for the deeplink integration.
     */
    private final static String DEEPLINK_SUFFIX = "-DeepLink";



    /**
     * This is a sample implementation of getChannels() which shows how to retrieve channels from an
     * XML resource file and create programmatically inline.
     *
     * There are 13 channels retrieved here and then inserted into the TIF Database.
     *
     * Channels 1, 2 and 3 are retrieved from the resource XML file: rich_tv_input_xmltv_feed.xml
     * Channels 4 - 13 are created programmatically in this method. Channels 9-13 will also appear on
     * Genre row in the Live Tab, if Live Tab is available in the Marketplace.
     *
     * Channels 1 and 2 are deeplink integrated meaning we insert a URI to launch this app
     * to playback content in a custom player. This URI is invoked when a user selects the channel
     * in the Fire TV UI.
     *
     * Channels 3, 4 and 9-13 are integrated with the Fire TV Native Player meaning they will be played
     * as part of the Fire TV UI - this uses the same mechanism used to implement preview playback.
     *
     * Channels 5 - 8 are integrated with external metadata meaning these channels receive all
     * channel metadata and program lineup data directly through Fire TV services. This app is only
     * responsible for inserting the external ID. Note: For this sample app, these channels do not
     * invoke playback but simply serve as an example of leveraging external metadata.
     * These could use either a deeplink integration OR the Fire TV Native Player for playback.
     *
     * @return list of channels to be inserted into the TIF database.
     */
    @Override
    public List<Channel> getChannels() {
        // Add channels through an XMLTV file
        XmlTvParser.TvListing listings = RichFeedUtil.getRichTvListings(this);
        List<Channel> channelList = new ArrayList<>();

        for (Channel channel : listings.getChannels()) {
            // The first two channels in the XML feed are intended to be used with a deeplink integration
            // See res/raw/rich_tv_input_xmltv_feed.xml for the channel and program data
            if (channel.getDisplayName().endsWith(DEEPLINK_SUFFIX)) {

                // Here is just an example that provider can also use deeplink to launch its own Player Activity
                // Please make change accordingly if custom features are needed

                Intent playbackDeepLinkIntent = new Intent();
                playbackDeepLinkIntent.setComponent(new ComponentName(PACKAGE_NAME, DEEP_LINK_ACTIVITY_CLASS_NAME));
                playbackDeepLinkIntent.putExtra(CHANNEL_DEEP_LINK_INTENT_PRIM_KEY, channel.getOriginalNetworkId());
                playbackDeepLinkIntent.putExtra(CHANNEL_DEEP_LINK_INTENT_SEC_KEY, INPUT_ID);

                InternalProviderData internalProviderDataPlaybackUri = new InternalProviderData();
                internalProviderDataPlaybackUri.setPlaybackDeepLinkUri(playbackDeepLinkIntent.toUri(Intent.URI_INTENT_SCHEME));

                channelList.add(new Channel.Builder(channel)
                        .setInternalProviderData(internalProviderDataPlaybackUri)
                        .build());
            } else {
                channelList.add(channel);
            }
        }

        // Add a channel programmatically
        Channel channelTears = new Channel.Builder()
                .setDisplayName(MPEG_DASH_CHANNEL_NAME)
                .setDisplayNumber(MPEG_DASH_CHANNEL_NUMBER)
                .setChannelLogo(MPEG_DASH_CHANNEL_LOGO)
                .setOriginalNetworkId(MPEG_DASH_ORIGINAL_NETWORK_ID)
                .build();
        channelList.add(channelTears);

        // Add channels which will receive external metadata.  For channels whose metadata is supplied by Gracenote,
        // use the Gracenote channel IDs.  If your organization integrates directly with Amazon's catalog to supply
        // station metadata, use the station IDs defined during catalog integration.
        List<String> externalGracenoteIds = Arrays.asList("10051", "10057", "10138", "58780");
        int startChannelNum = 5;
        for (String id : externalGracenoteIds) {
            InternalProviderData internalProviderData = new InternalProviderData();
            internalProviderData.setExternalIdType(GRACENOTE_ID);
            internalProviderData.setExternalIdValue(id);

            Channel externalMetadataChannel = new Channel.Builder()
                    .setDisplayName("Channel " + id)
                    .setDisplayNumber(Integer.toString(startChannelNum++))
                    .setOriginalNetworkId(Integer.parseInt(id))
                    .setInternalProviderData(internalProviderData)
                    .build();

            channelList.add(externalMetadataChannel);
        }

        //Add channel programmatically with Genre information
        List<Channel> genreChannels = Arrays.asList(
                new Channel.Builder()
                        .setDisplayName(GENRE_CHANNEL_NAMES.get(0))
                        .setDisplayNumber("9")
                        .setChannelLogo(GENRE_CHANNEL_LOGO)
                        .setOriginalNetworkId(110)
                        .setTifExtension(new TifExtension.Builder()
                                .setGenre(SPORTS_GENRE)
                                .build())
                        .build(),
                new Channel.Builder()
                        .setDisplayName(GENRE_CHANNEL_NAMES.get(1))
                        .setDisplayNumber("10")
                        .setChannelLogo(GENRE_CHANNEL_LOGO)
                        .setOriginalNetworkId(111)
                        .setTifExtension(new TifExtension.Builder()
                                .setGenre(SPORTS_GENRE)
                                .build())
                        .build(),
                new Channel.Builder()
                        .setDisplayName(GENRE_CHANNEL_NAMES.get(2))
                        .setDisplayNumber("11")
                        .setChannelLogo(GENRE_CHANNEL_LOGO)
                        .setOriginalNetworkId(112)
                        .setTifExtension(new TifExtension.Builder()
                                .setGenre(SPORTS_GENRE)
                                .build())
                        .build(),
                new Channel.Builder()
                        .setDisplayName(GENRE_CHANNEL_NAMES.get(3))
                        .setDisplayNumber("12")
                        .setChannelLogo(GENRE_CHANNEL_LOGO)
                        .setOriginalNetworkId(113)
                        .setTifExtension(new TifExtension.Builder()
                                .setGenre(SPORTS_GENRE)
                                .build())
                        .build(),
                new Channel.Builder()
                        .setDisplayName(GENRE_CHANNEL_NAMES.get(4))
                        .setDisplayNumber("13")
                        .setChannelLogo(GENRE_CHANNEL_LOGO)
                        .setOriginalNetworkId(114)
                        .setTifExtension(new TifExtension.Builder()
                                .setGenre(SPORTS_GENRE)
                                .build())
                        .build()
        );
        channelList.addAll(genreChannels);

        return channelList;
    }

    @Override
    public List<Program> getProgramsForChannel(Uri channelUri, Channel channel, long startMs,
                                               long endMs) {
        // Programmatically add channel
        List<Program> programsTears = new ArrayList<>();
        InternalProviderData internalProviderData = new InternalProviderData();
        internalProviderData.setVideoType(Util.TYPE_DASH);
        internalProviderData.setVideoUrl(TEARS_OF_STEEL_SOURCE);

        String description;
        String thumbnail;

        if (channel.getDisplayName().equals(MPEG_DASH_CHANNEL_NAME)) {
            description = TEARS_OF_STEEL_DESCRIPTION;
            thumbnail = MPEG_DASH_PROGRAM_URL;
        } else if (GENRE_CHANNEL_NAMES.contains(channel.getDisplayName())) {
            description = "Program for genre tagged channel: " + TEARS_OF_STEEL_DESCRIPTION;
            thumbnail = GENRE_PROGRAM_URL;
        } else if (channel.getInternalProviderData() != null && channel.getInternalProviderData()
                .getExternalIdValue() != null) {
            // Has external metadata provided , no need to insert programs
            return Collections.emptyList();
        } else {
            // Is an XMLTV Channel
            XmlTvParser.TvListing listings = RichFeedUtil.getRichTvListings(getApplicationContext());
            return listings.getPrograms(channel);
        }
        programsTears.add(new Program.Builder()
                    .setTitle(TEARS_OF_STEEL_TITLE)
                    .setStartTimeUtcMillis(TEARS_OF_STEEL_START_TIME_MS)
                    .setEndTimeUtcMillis(TEARS_OF_STEEL_START_TIME_MS + TEARS_OF_STEEL_DURATION_MS)
                    .setDescription(description)
                    .setCanonicalGenres(new String[] {TvContract.Programs.Genres.TECH_SCIENCE,
                            TvContract.Programs.Genres.MOVIES})
                    .setContentRatings(new TvContentRating[]{TvContentRating.createRating(ANDROID_TV_RATING,
                            "US_TV", "US_TV_PG")})
                    .setPosterArtUri(TEARS_OF_STEEL_ART)
                    .setThumbnailUri(thumbnail)
                    .setInternalProviderData(internalProviderData)
                    .build());
            return programsTears;
    }
}
