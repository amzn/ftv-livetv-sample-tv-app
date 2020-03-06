package com.google.android.media.tv.companionlibrary.utils;

import android.content.ContentResolver;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * This is a cache to store the Channel Information
 * This class is not mandatory. It is a cache to cache the channel uri
 * Refer to Readme file 3p can implemented its own cache
 */
public class SampleChannelListManager
{
    private static SampleChannelListManager sMSampleChannelListManager = null;

    private static final String TAG = "SampleListMng";

    //Key: Channel's originalNetworkId, Value: the channel Uri
    private final Map<Integer, Uri> mChannelUriMap;


    private SampleChannelListManager(){
        mChannelUriMap = new HashMap<>();
    }

    public static SampleChannelListManager getInstance(){
        if(sMSampleChannelListManager == null) {
            sMSampleChannelListManager = new SampleChannelListManager();
        }

        return sMSampleChannelListManager;
    }

    /**
     * The method to put the channel uri by using the channel originalNetworkId
     * @param originalNetworkId  The channel's originalNetworkId
     * @param channelUri         The channel's Uri in tv.db
     */
    public void addChannelUri(final int originalNetworkId, final Uri channelUri){
         mChannelUriMap.put(originalNetworkId,channelUri);
    }

    /**
     * The method to get the channel uri by using the channel originalNetworkId
     * @param originalNetworkId  The channel's originalNetworkId
     * @return the channel's Url
     */
    public Uri getChannelUri(final int originalNetworkId){
        return mChannelUriMap.get(originalNetworkId);
    }

    /**
     * The method to update the channel list given an input Id
     * @param resolver  The ContentResolver
     * @param inputId   The Channel Input ID
     */
    public void updateChannelList(final ContentResolver resolver, final String inputId){

        Uri channelsUri = TvContract.buildChannelsUriForInput(inputId);
        String[] projection = {TvContract.Channels._ID, TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID};

        try (Cursor cursor = resolver.query(channelsUri, projection, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                long rowId = cursor.getLong(0);
                int originalNetworkId = cursor.getInt(1);

                Uri uri = TvContract.buildChannelUri(rowId);
                addChannelUri(originalNetworkId,uri);
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to get channels", e);
        }
    }

    /**
     * Clear the cache
     */
    public void clear(){
        mChannelUriMap.clear();
    }
}
