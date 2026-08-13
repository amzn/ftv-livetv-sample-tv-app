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

import android.content.Context;
import android.util.Log;
import android.util.Xml;

import com.example.android.sampletvinput.R;
import com.example.android.sampletvinput.SampleChannelManager;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Parses the bundled sample XML feed (res/raw/rich_tv_input_xmltv_feed.xml) to extract
 * channel and program data, standing in for the catalog or EPG data a real app would
 * otherwise fetch from its data source.
 *
 * Adapted from XmlTvParser.java (TIF Companion Library, Apache License 2.0).
 */
public class RichFeedUtil {
    private static final String TAG = "RichFeedUtil";

    private static List<ChannelInfo> sChannels;
    private static Map<String, List<ProgramInfo>> sPrograms; // channelId -> programs

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US);
    static {
        DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    // XML tag and attribute names, mirroring the original XmlTvParser's constants.
    private static final String TAG_CHANNEL = "channel";
    private static final String TAG_DISPLAY_NAME = "display-name";
    private static final String TAG_DISPLAY_NUMBER = "display-number";
    private static final String TAG_ICON = "icon";
    private static final String TAG_PROGRAM = "programme";
    private static final String TAG_TITLE = "title";
    private static final String TAG_DESC = "desc";
    private static final String TAG_LONG_DESC = "long-desc";
    private static final String TAG_EPISODE_TITLE = "episode-title";
    private static final String TAG_RATING = "rating";
    private static final String TAG_VALUE = "value";

    private static final String ATTR_ID = "id";
    private static final String ATTR_REPEAT_PROGRAMS = "repeat-programs";
    private static final String ATTR_SRC = "src";
    private static final String ATTR_SYSTEM = "system";
    private static final String ATTR_CHANNEL = "channel";
    private static final String ATTR_START = "start";
    private static final String ATTR_STOP = "stop";
    private static final String ATTR_THUMBNAIL_URI = "thumbnail-uri";
    private static final String ATTR_VIDEO_SRC = "video-src";
    private static final String ATTR_VIDEO_TYPE = "video-type";

    // The standard "domain" string for Android TV content ratings.
    private static final String RATING_SYSTEM_ANDROID_TV = "com.android.tv";

    /**
     * Channel data extracted from the XML feed.
     */
    public static class ChannelInfo {
        public String id;              // XML channel id attribute
        public String displayName;
        public String displayNumber;
        public String logoUrl;
        public int originalNetworkId;
        public boolean repeatPrograms;
    }

    /**
     * Program data extracted from the XML feed.
     * Times are stored as offsets from the first program's start time,
     * so they can be shifted to current time during insertion.
     */
    public static class ProgramInfo {
        public String title;
        public String description;
        public String longDescription;
        public String episodeTitle;
        public String posterArtUri;
        public String thumbnailUri;
        public String videoUrl;
        public int videoType;          // SOURCE_TYPE_HTTP_PROGRESSIVE, SOURCE_TYPE_HLS, etc.
        public String contentRating;
        public long startOffsetMs;     // offset from "now" for rolling schedule
        public long endOffsetMs;       // offset from "now" for rolling schedule
    }

    private RichFeedUtil() {}

    /**
     * Returns the list of channels from the XML feed.
     */
    public static List<ChannelInfo> getChannels(Context context) {
        if (sChannels == null) {
            parseFeed(context);
        }
        return sChannels;
    }

    /**
     * Returns the list of programs for a given channel ID from the XML feed.
     */
    public static List<ProgramInfo> getPrograms(Context context, String channelId) {
        if (sPrograms == null) {
            parseFeed(context);
        }
        return sPrograms.get(channelId);
    }

    /**
     * Parses the local XMLTV feed using XmlPullParser.
     */
    private static void parseFeed(Context context) {
        sChannels = new ArrayList<>();
        sPrograms = new HashMap<>();

        try (InputStream inputStream = context.getResources().openRawResource(
                R.raw.rich_tv_input_xmltv_feed)) {

            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(inputStream, "UTF-8");

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    if (TAG_CHANNEL.equalsIgnoreCase(parser.getName())) {
                        parseChannel(parser);
                    } else if (TAG_PROGRAM.equalsIgnoreCase(parser.getName())) {
                        parseProgram(parser);
                    }
                }
                eventType = parser.next();
            }

            // Convert absolute program times to relative offsets for rolling schedule
            convertToRelativeOffsets();

        } catch (XmlPullParserException | IOException e) {
            Log.e(TAG, "Error parsing XML feed", e);
        }
    }

    /**
     * Parses a <channel> element.
     */
    private static void parseChannel(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        ChannelInfo channel = new ChannelInfo();

        for (int i = 0; i < parser.getAttributeCount(); ++i) {
            String attr = parser.getAttributeName(i);
            String value = parser.getAttributeValue(i);
            if (ATTR_ID.equalsIgnoreCase(attr)) {
                channel.id = value;
            } else if (ATTR_REPEAT_PROGRAMS.equalsIgnoreCase(attr)) {
                channel.repeatPrograms = "TRUE".equalsIgnoreCase(value);
            }
        }

        // Extract original network ID from channel id (last segment after the last dot)
        try {
            String[] parts = channel.id.split("\\.");
            channel.originalNetworkId = Integer.parseInt(parts[parts.length - 1]);
        } catch (NumberFormatException e) {
            channel.originalNetworkId = channel.id.hashCode();
        }

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.getEventType() == XmlPullParser.START_TAG) {
                if (TAG_DISPLAY_NAME.equalsIgnoreCase(parser.getName())
                        && channel.displayName == null) {
                    channel.displayName = parser.nextText();
                } else if (TAG_DISPLAY_NUMBER.equalsIgnoreCase(parser.getName())
                        && channel.displayNumber == null) {
                    channel.displayNumber = parser.nextText();
                } else if (TAG_ICON.equalsIgnoreCase(parser.getName())
                        && channel.logoUrl == null) {
                    channel.logoUrl = parseIconSrc(parser);
                }
            } else if (TAG_CHANNEL.equalsIgnoreCase(parser.getName())
                    && parser.getEventType() == XmlPullParser.END_TAG) {
                break;
            }
        }

        sChannels.add(channel);
        // Initialize program list for this channel
        if (!sPrograms.containsKey(channel.id)) {
            sPrograms.put(channel.id, new ArrayList<>());
        }
    }

    /**
     * Parses an <icon> element and returns its src attribute, consuming through the
     * element's own end tag.
     */
    private static String parseIconSrc(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        String src = null;
        for (int i = 0; i < parser.getAttributeCount(); ++i) {
            if (ATTR_SRC.equalsIgnoreCase(parser.getAttributeName(i))) {
                src = parser.getAttributeValue(i);
            }
        }
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (TAG_ICON.equalsIgnoreCase(parser.getName())
                    && parser.getEventType() == XmlPullParser.END_TAG) {
                break;
            }
        }
        return src;
    }

    /**
     * Parses a <programme> element.
     */
    private static void parseProgram(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        ProgramInfo program = new ProgramInfo();
        String channelId = null;

        for (int i = 0; i < parser.getAttributeCount(); ++i) {
            String attr = parser.getAttributeName(i);
            String value = parser.getAttributeValue(i);
            if (ATTR_CHANNEL.equalsIgnoreCase(attr)) {
                channelId = value;
            } else if (ATTR_START.equalsIgnoreCase(attr)) {
                program.startOffsetMs = parseTime(value);
            } else if (ATTR_STOP.equalsIgnoreCase(attr)) {
                program.endOffsetMs = parseTime(value);
            } else if (ATTR_THUMBNAIL_URI.equalsIgnoreCase(attr)) {
                program.thumbnailUri = value;
            } else if (ATTR_VIDEO_SRC.equalsIgnoreCase(attr)) {
                program.videoUrl = value;
            } else if (ATTR_VIDEO_TYPE.equalsIgnoreCase(attr)) {
                program.videoType = parseVideoType(value);
            }
        }

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.getEventType() == XmlPullParser.START_TAG) {
                if (TAG_TITLE.equalsIgnoreCase(parser.getName()) && program.title == null) {
                    program.title = parser.nextText();
                } else if (TAG_DESC.equalsIgnoreCase(parser.getName())
                        && program.description == null) {
                    program.description = parser.nextText();
                } else if (TAG_LONG_DESC.equalsIgnoreCase(parser.getName())
                        && program.longDescription == null) {
                    program.longDescription = parser.nextText();
                } else if (TAG_EPISODE_TITLE.equalsIgnoreCase(parser.getName())
                        && program.episodeTitle == null) {
                    program.episodeTitle = parser.nextText();
                } else if (TAG_ICON.equalsIgnoreCase(parser.getName())
                        && program.posterArtUri == null) {
                    program.posterArtUri = parseIconSrc(parser);
                } else if (TAG_RATING.equalsIgnoreCase(parser.getName())) {
                    Rating rating = parseRating(parser);
                    if (RATING_SYSTEM_ANDROID_TV.equals(rating.system)) {
                        program.contentRating = rating.value;
                    }
                }
            } else if (TAG_PROGRAM.equalsIgnoreCase(parser.getName())
                    && parser.getEventType() == XmlPullParser.END_TAG) {
                break;
            }
        }

        // Add to the channel's program list
        List<ProgramInfo> programList = sPrograms.get(channelId);
        if (programList != null) {
            programList.add(program);
        }
    }

    /**
     * A <rating> element's system and value.
     */
    private static class Rating {
        final String system;
        final String value;

        Rating(String system, String value) {
            this.system = system;
            this.value = value;
        }
    }

    /**
     * Parses a <rating> element, consuming through the element's own end tag.
     */
    private static Rating parseRating(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        String system = null;
        for (int i = 0; i < parser.getAttributeCount(); ++i) {
            if (ATTR_SYSTEM.equalsIgnoreCase(parser.getAttributeName(i))) {
                system = parser.getAttributeValue(i);
            }
        }
        String value = null;
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.getEventType() == XmlPullParser.START_TAG) {
                if (TAG_VALUE.equalsIgnoreCase(parser.getName())) {
                    value = parser.nextText();
                }
            } else if (TAG_RATING.equalsIgnoreCase(parser.getName())
                    && parser.getEventType() == XmlPullParser.END_TAG) {
                break;
            }
        }
        return new Rating(system, value);
    }

    /**
     * Converts absolute program times to relative offsets from "now".
     * This allows creating a rolling schedule each time sync runs.
     */
    private static void convertToRelativeOffsets() {
        for (Map.Entry<String, List<ProgramInfo>> entry : sPrograms.entrySet()) {
            List<ProgramInfo> programs = entry.getValue();
            if (programs.isEmpty()) continue;

            // Find the earliest start time in this channel's programs
            long earliestStart = Long.MAX_VALUE;
            for (ProgramInfo p : programs) {
                if (p.startOffsetMs < earliestStart) {
                    earliestStart = p.startOffsetMs;
                }
            }

            // Convert to offsets relative to earliest start
            for (ProgramInfo p : programs) {
                p.startOffsetMs = p.startOffsetMs - earliestStart;
                p.endOffsetMs = p.endOffsetMs - earliestStart;
            }
        }
    }

    /**
     * Parses an XMLTV timestamp string to milliseconds.
     */
    private static long parseTime(String timeStr) {
        if (timeStr == null) return 0;
        try {
            return DATE_FORMAT.parse(timeStr).getTime();
        } catch (ParseException e) {
            Log.e(TAG, "Error parsing time: " + timeStr, e);
            return 0;
        }
    }

    /**
     * Converts a video type string to an integer constant.
     */
    private static int parseVideoType(String videoType) {
        if (videoType == null) return SampleChannelManager.SOURCE_TYPE_HTTP_PROGRESSIVE;
        switch (videoType) {
            case "HLS":
                return SampleChannelManager.SOURCE_TYPE_HLS;
            case "MPEG_DASH":
                return SampleChannelManager.SOURCE_TYPE_MPEG_DASH;
            case "HTTP_PROGRESSIVE":
            default:
                return SampleChannelManager.SOURCE_TYPE_HTTP_PROGRESSIVE;
        }
    }
}
