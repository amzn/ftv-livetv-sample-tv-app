/*
 * Copyright 2016 Google Inc. All rights reserved.
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

package com.google.android.media.tv.companionlibrary.model;

import androidx.annotation.NonNull;
import com.google.android.media.tv.companionlibrary.utils.TvContractUtils;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Iterator;

/**
 * This is a serialized class used for storing and retrieving serialized data from
 * {@link android.media.tv.TvContract.Channels#COLUMN_INTERNAL_PROVIDER_DATA},
 * {@link android.media.tv.TvContract.Programs#COLUMN_INTERNAL_PROVIDER_DATA}, and
 * {@link android.media.tv.TvContract.RecordedPrograms#COLUMN_INTERNAL_PROVIDER_DATA}.
 *
 * In addition to developers being able to add custom attributes to this data type, there are
 * pre-defined values.
 */
public class InternalProviderData {
    private static final String TAG = "InternalProviderData";
    private static final boolean DEBUG = true;

    /**
     * Keys used by Fire TV to provide metadata from an external source.
     * These keys should be set on a channel.
     */
    private static final String KEY_AMZN_EXTERNAL_ID_TYPE = "externalIdType";
    private static final String KEY_AMZN_EXTERNAL_ID_VALUE = "externalIdValue";

    /**
     * Key used by Fire TV to enable deeplinking into your app for playback.
     * This key should be set on a channel.
     */
    private static final String KEY_AMZN_PLAYBACK_DEEP_LINK_URI = "playbackDeepLinkUri";

    /**
     * Keys used by this sample app to support storage of program playback information.
     * These keys are set on programs.
     */
    private static final String KEY_VIDEO_TYPE = "type";
    private static final String KEY_VIDEO_URL = "url";

    /**
     * Key used to support storage of arbitrary data.
     */
    private static final String KEY_CUSTOM_DATA = "custom";

    private JSONObject mJsonObject;

    /**
     * Creates a new empty object
     */
    public InternalProviderData() {
        mJsonObject = new JSONObject();
    }

    /**
     * Creates a new object and attempts to populate from the provided String
     *
     * @param data Correctly formatted InternalProviderData
     * @throws ParseException If data is not formatted correctly
     */
    public InternalProviderData(@NonNull String data) throws ParseException {
        try {
            mJsonObject = new JSONObject(data);
        } catch (JSONException e) {
            throw new ParseException(e.getMessage());
        }
    }

    /**
     * Creates a new object and attempts to populate by obtaining the String representation of the
     * provided byte array
     *
     * @param bytes Byte array corresponding to a correctly formatted String representation of
     * InternalProviderData
     * @throws ParseException If data is not formatted correctly
     */
    public InternalProviderData(@NonNull byte[] bytes) throws ParseException {
        try {
            mJsonObject = new JSONObject(new String(bytes));
        } catch (JSONException e) {
            throw new ParseException(e.getMessage());
        }
    }

    private int jsonHash(JSONObject jsonObject) {
        int hashSum = 0;
        Iterator<String> keys = jsonObject.keys();
        while(keys.hasNext()) {
            String key = keys.next();
            try {
                if (jsonObject.get(key) instanceof JSONObject) {
                    // This is a branch, get hash of this object recursively
                    JSONObject branch = jsonObject.getJSONObject(key);
                    hashSum += jsonHash(branch);
                } else {
                    // If this key does not link to a JSONObject, get hash of leaf
                    hashSum += key.hashCode() + jsonObject.get(key).hashCode();
                }
            } catch (JSONException ignored) {
            }
        }
        return hashSum;
    }

    @Override
    public int hashCode() {
        // Recursively get the hashcode from all internal JSON keys and values
        return jsonHash(mJsonObject);
    }

    private boolean jsonEquals(JSONObject json1, JSONObject json2) {
        Iterator<String> keys = json1.keys();
        while(keys.hasNext()) {
            String key = keys.next();
            try {
                if (json1.get(key) instanceof JSONObject) {
                    // This is a branch, check equality of this object recursively
                    JSONObject thisBranch = json1.getJSONObject(key);
                    JSONObject otherBranch = json2.getJSONObject(key);
                    return jsonEquals(thisBranch, otherBranch);
                } else {
                    // If this key does not link to a JSONObject, check equality of leaf
                    if (!json1.get(key).equals(json2.get(key))) {
                        // The VALUE of the KEY does not match
                        return false;
                    }
                }
            } catch (JSONException e) {
                return false;
            }
        }
        // Confirm that no key has been missed in the check
        return json1.length() == json2.length();
    }

    /**
     * Tests that the value of each key is equal. Order does not matter.
     *
     * @param obj The object you are comparing to.
     * @return Whether the value of each key between both objects is equal.
     */
    @Override
    public boolean equals(Object obj) {
        if (obj == null || ! (obj instanceof InternalProviderData)) {
            return false;
        }
        JSONObject otherJsonObject = ((InternalProviderData) obj).mJsonObject;
        return jsonEquals(mJsonObject, otherJsonObject);
    }

    @Override
    public String toString() {
        return mJsonObject.toString();
    }


    /**
     * Sets the external ID type for the channel.
     * This enables retrieval of metadata from an external source.
     *
     * @param externalIdType The external ID type, refer to integration documentation for valid values.
     */
    public void setExternalIdType(String externalIdType) {
        try {
            mJsonObject.put(KEY_AMZN_EXTERNAL_ID_TYPE, externalIdType);
        } catch (JSONException ignored) {
        }
    }

    /**
     * Gets the external ID type for the channel.
     *
     * @return The external ID type
     */
    public String getExternalIdType() {
        if (mJsonObject.has(KEY_AMZN_EXTERNAL_ID_TYPE)) {
            try {
                return mJsonObject.getString(KEY_AMZN_EXTERNAL_ID_TYPE);
            } catch (JSONException ignored) {
            }
        }
        return null;
    }

    /**
     * Sets the external ID value for the channel. Both type and value must be set.
     *
     * @param externalIdValue The external ID value
     */
    public void setExternalIdValue(String externalIdValue) {
        try {
            mJsonObject.put(KEY_AMZN_EXTERNAL_ID_VALUE, externalIdValue);
        } catch (JSONException ignored) {
        }
    }

    /**
     * Gets the external ID value for the channel.
     *
     * @return The external ID value
     */
    public String getExternalIdValue() {
        if (mJsonObject.has(KEY_AMZN_EXTERNAL_ID_VALUE)) {
            try {
                return mJsonObject.getString(KEY_AMZN_EXTERNAL_ID_VALUE);
            } catch (JSONException ignored) {
            }
        }
        return null;
    }

    /**
     * Sets the deeplink URI for the specific channel. Fire TV will fire this intent when the user
     * wants to play a channel.
     *
     * @param playbackDeepLinkUri The uri to start playback of the specified channel.
     */
    public void setPlaybackDeepLinkUri(String playbackDeepLinkUri) {
        try {
            mJsonObject.put(KEY_AMZN_PLAYBACK_DEEP_LINK_URI, playbackDeepLinkUri);
        } catch (JSONException ignored) {
        }
    }

    /**
     * Gets the video type of the program.
     *
     * @return The video type of the program, -1 if no value has been given.
     */
    public int getVideoType() {
        if (mJsonObject.has(KEY_VIDEO_TYPE)) {
            try {
                return mJsonObject.getInt(KEY_VIDEO_TYPE);
            } catch (JSONException ignored) {
            }
        }
        return TvContractUtils.SOURCE_TYPE_INVALID;
    }

    /**
     * Sets the video type of the program.
     *
     * @param videoType The video source type. Could be {@link TvContractUtils#SOURCE_TYPE_HLS},
     * {@link TvContractUtils#SOURCE_TYPE_HTTP_PROGRESSIVE},
     * or {@link TvContractUtils#SOURCE_TYPE_MPEG_DASH}.
     */
    public void setVideoType(int videoType) {
        try {
            mJsonObject.put(KEY_VIDEO_TYPE, videoType);
        } catch (JSONException ignored) {
        }
    }

    /**
     * Gets the video url of the program if valid.
     *
     * @return The video url of the program if valid, null if no value has been given.
     */
    public String getVideoUrl() {
        if (mJsonObject.has(KEY_VIDEO_URL)) {
            try {
                return mJsonObject.getString(KEY_VIDEO_URL);
            } catch (JSONException ignored) {
            }
        }
        return null;
    }

    /**
     * Sets the video url of the program.
     *
     * @param videoUrl A valid url pointing to the video to be played.
     */
    public void setVideoUrl(String videoUrl) {
        try {
            mJsonObject.put(KEY_VIDEO_URL, videoUrl);
        } catch (JSONException ignored) {
        }
    }

    /**
     * Adds some custom data to the InternalProviderData.
     * Developers are encouraged to use this blob to store arbitrary data.
     *
     * @param key The key for this data
     * @param value The value this data should take
     * @return This InternalProviderData object to allow for chaining of calls
     * @throws ParseException If there is a problem adding custom data
     */
    public InternalProviderData put(String key, Object value) throws ParseException {
        try {
            if (!mJsonObject.has(KEY_CUSTOM_DATA)) {
                mJsonObject.put(KEY_CUSTOM_DATA, new JSONObject());
            }
            mJsonObject.getJSONObject(KEY_CUSTOM_DATA).put(key, String.valueOf(value));
        } catch (JSONException e) {
            throw new ParseException(e.getMessage());
        }
        return this;
    }

    /**
     * Gets some previously added custom data stored in InternalProviderData.
     *
     * @param key The key assigned to this data
     * @return The value of this key if it has been defined. Returns null if the key is not found.
     * @throws ParseException If there is a problem getting custom data
     */
    public Object get(String key) throws ParseException {
        if (! mJsonObject.has(KEY_CUSTOM_DATA)) {
            return null;
        }
        try {
            return mJsonObject.getJSONObject(KEY_CUSTOM_DATA).opt(key);
        } catch (JSONException e) {
            throw new ParseException(e.getMessage());
        }
    }

    /**
     * Checks whether a custom key is found in InternalProviderData.
     *
     * @param key The key assigned to this data
     * @return Whether this key is found.
     * @throws ParseException If there is a problem checking custom data
     */
    public boolean has(String key) throws ParseException {
        if (! mJsonObject.has(KEY_CUSTOM_DATA)) {
            return false;
        }
        try {
            return mJsonObject.getJSONObject(KEY_CUSTOM_DATA).has(key);
        } catch (JSONException e) {
            throw new ParseException(e.getMessage());
        }
    }

    /**
     * This exception is thrown when an error occurs in getting or setting data for the
     * InternalProviderData.
     */
    public class ParseException extends JSONException {
        public ParseException(String s) {
            super(s);
        }
    }
}
