package com.google.android.media.tv.companionlibrary.utils;

import android.content.ContentProviderOperation;
import android.content.ContentValues;
import android.media.tv.TvContract;
import android.util.Log;
import android.util.LongSparseArray;

import com.google.android.media.tv.companionlibrary.model.Channel;

import java.util.ArrayList;
import java.util.List;

public class ConverterUtils {

    private static final String TAG = ConverterUtils.class.getSimpleName();

    public static List<ContentValues> convertToContentValues(LongSparseArray<Channel> channels, String inputId, String packageName) {
        List<ContentValues> desiredContentValues = new ArrayList<>();

        for (int i = 0; i < channels.size(); i++) {
            long key = channels.keyAt(i);
            Channel channel = channels.get(key);
            desiredContentValues.add(channel.toContentValues(inputId, packageName));
        }

        return desiredContentValues;
    }

    public static List<ContentValues> convertToTifExtensionContentValues(List<TifExtensionChannel> tifExtensionChannels) {
        List<ContentValues> newContentValues = new ArrayList<>();

        for (TifExtensionChannel tifExtensionChannel : tifExtensionChannels) {
            newContentValues.add(tifExtensionChannel.toContentValues());

        }

        return newContentValues;
    }

    public static List<TifExtensionChannel> convertToTifExtensionChannel(List<Channel> currentTifChannels, LongSparseArray<Channel> desiredChannels) {
        List<TifExtensionChannel> tifExtensionChannels = new ArrayList<>();

        for (Channel tifChannel : currentTifChannels) {
            Channel lookupChannel = desiredChannels.get(tifChannel.getOriginalNetworkId());
            if (lookupChannel != null && lookupChannel.getTifExtension() != null) {
                TifExtensionChannel tifExtensionChannel = new TifExtensionChannel.Builder()
                        .setChannelId(tifChannel.getId())
                        .setInputId(tifChannel.getInputId())
                        .setTifExtension(lookupChannel.getTifExtension())
                        .build();
                Log.d(TAG, "Found TIF extension channel with ID " + tifChannel.getId() + " and genre " + lookupChannel.getTifExtension().getGenre());
                tifExtensionChannels.add(tifExtensionChannel);
            }
        }

        return tifExtensionChannels;
    }

    public static ArrayList<ContentProviderOperation> convertToUpdateContentProviderOperation(List<ContentValues> contentValues) {
        ArrayList<ContentProviderOperation> contentProviderOperations = new ArrayList<>();

        for (ContentValues contentValue : contentValues) {
            contentProviderOperations.add(
                    ContentProviderOperation
                            .newUpdate(TvContract.buildChannelUri((Long) contentValue.get(TvContract.Channels._ID)))
                            .withValues(contentValue)
                            .build()
            );
        }

        return contentProviderOperations;
    }

    public static ArrayList<ContentProviderOperation> convertToInsertContentProviderOperation(List<ContentValues> contentValues) {
        ArrayList<ContentProviderOperation> contentProviderOperations = new ArrayList<>();

        for (ContentValues contentValue : contentValues) {
            contentProviderOperations.add(
                    ContentProviderOperation
                            .newInsert(TvContract.Channels.CONTENT_URI)
                            .withValues(contentValue)
                            .build()
            );
        }

        return contentProviderOperations;
    }

    public static ArrayList<ContentProviderOperation> convertToDeleteContentProviderOperation(List<Long> channelsIds) {
        ArrayList<ContentProviderOperation> contentProviderOperations = new ArrayList<>();

        for (Long id : channelsIds) {
            contentProviderOperations.add(
                    ContentProviderOperation
                            .newDelete(TvContract.buildChannelUri(id))
                            .build()
            );
        }

        return contentProviderOperations;
    }
}
