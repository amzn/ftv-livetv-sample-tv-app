package com.google.android.media.tv.companionlibrary.utils;

import android.content.ContentValues;

import com.google.android.media.tv.companionlibrary.model.TifExtension;

/**
 * A convenience class to create and insert TifExtension channel entries into the database.
 */
public class TifExtensionChannel {
    private String inputId;
    private long channelId;
    private TifExtension tifExtension;

    private TifExtensionChannel(Builder builder) {
        inputId = builder.inputId;
        channelId = builder.channelId;
        tifExtension = builder.tifExtension;
    }

    /**
     * @return The value of {@link TifExtensionContract.Channels#COLUMN_INPUT_ID} for the channel.
     */
    public String getInputId() { return inputId; }

    /**
     * @return The value of {@link TifExtensionContract.Channels#COLUMN_CHANNEL_ID} for the channel.
     */
    public long getChannelId() { return channelId; }

    /**
     * @return The value of {@link TifExtension} for the channel.
     */
    public TifExtension getTifExtension() { return tifExtension; }

    /**
     * @return The fields of the TifExtension channel in the ContentValues format to be easily
     * inserted into the TV Input Framework database.
     */
    public ContentValues toContentValues() {
        ContentValues contentValues = new ContentValues();

        contentValues.put(TifExtensionContract.Channels.COLUMN_CHANNEL_ID, channelId);

        contentValues.put(TifExtensionContract.Channels.COLUMN_INPUT_ID, inputId);
        if (getTifExtension().getGenre() != null) {
            contentValues.put(TifExtensionContract.Channels.COLUMN_GENRE,
                    getTifExtension().getGenre());
        }

        return contentValues;
    }

    public static class Builder {
        private String inputId;
        private long channelId;
        private TifExtension tifExtension;

        /**
         * Sets the Input Id of the Channel.
         *
         * @param inputId The value of {@link TifExtensionContract.Channels#COLUMN_INPUT_ID}
         *               for the channel.
         * @return This Builder object to allow for chaining of calls to builder methods.
         */
        public Builder setInputId(String inputId) {
            this.inputId = inputId;
            return this;
        }

        /**
         * Sets the Channel Id of the Channel using the channel URI.
         *
         * @param channelId The value of {@link TifExtensionContract.Channels#COLUMN_CHANNEL_ID}
         *               for the channel using channel Uri.
         * @return This Builder object to allow for chaining of calls to builder methods.
         */
        public Builder setChannelId(long channelId) {
            this.channelId = channelId;
            return this;
        }

        /**
         * Sets the TifExtensions instance for the channel.
         *
         * @param tifExtension The value of {@link TifExtension} for the channel.
         * @return This Builder object to allow for chaining of calls to builder methods.
         */
        public Builder setTifExtension(TifExtension tifExtension) {
            this.tifExtension = tifExtension;
            return this;
        }

        public TifExtensionChannel build() { return new TifExtensionChannel(this); }
    }
}