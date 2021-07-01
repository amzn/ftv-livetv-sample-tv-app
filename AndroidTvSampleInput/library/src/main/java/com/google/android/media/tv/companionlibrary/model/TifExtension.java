package com.google.android.media.tv.companionlibrary.model;

import com.google.android.media.tv.companionlibrary.utils.TifExtensionContract;

/**
 * A convenience class to create TifExtension object for a TifExtension channel.
 */
public class TifExtension {
    private String mGenre;

    private TifExtension(Builder builder) {
        mGenre = builder.genre;
    }

    /**
     * @return The value of {@link TifExtensionContract.Channels#COLUMN_GENRE} for the channel.
     */
    public String getGenre() { return mGenre; }

    public static class Builder {

        private String genre;

        /**
         * Sets the genre of the channel.
         * Currently supports 'News' and 'Sports' genre values. For up-to-date Genre values,
         * check the documentation hub https://developer.amazon.com/docs//fire-tv/live-tv-resources.html
         * or ask your Amazon contact for the latest list.
         *
         * @param genre The genre corresponding to the channel.
         * @return This Builder object to allow for chaining of calls to builder methods.
         */
        public Builder setGenre(String genre) {
            this.genre = genre;
            return this;
        }

        public TifExtension build() {
            return new TifExtension(this);
        }
    }
}