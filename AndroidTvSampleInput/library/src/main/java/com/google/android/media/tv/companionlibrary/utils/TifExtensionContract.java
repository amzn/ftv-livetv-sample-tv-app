package com.google.android.media.tv.companionlibrary.utils;

import android.net.Uri;

/**
 * The TifExtension contract between the TV provider and Live Tv app
 */
public class TifExtensionContract {
    /**
     * The authority for the TifExtension Provider
     */
    public final static String AUTHORITY = "com.amazon.tv.livetv.tifextension";


    private static final String PATH_CHANNEL = "channel";


    public static final class Channels {

        /**
         * The content:// style URI for channel table.
         */
        public final static Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/" + PATH_CHANNEL);

        //============= Columns ==============//

        /**
         * Matches to input_id in TvContract.Channel table
         * This is a required field. @NonNull
         * Type: TEXT
         */
        public final static String COLUMN_INPUT_ID = "input_id";

        /**
         * Matches to channel_id in TvContract.Channel table
         * This is a required field. @NonNull
         * Type: TEXT
         */
        public final static String COLUMN_CHANNEL_ID = "channel_id";

        /**
         * The comma-separated genre string of this channel.
         * Channel with valid genre values will show inside corresponding Genre rows under LiveTab
         * Valid genre value is defined in the contract.
         * Invalid value would be ignored.
         * Type: TEXT Default Null
         */
        public final static String COLUMN_GENRE = "genre";
    }
}