package com.google.android.media.tv.companionlibrary.utils;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;
import android.util.LongSparseArray;

import com.google.android.media.tv.companionlibrary.model.Channel;

import java.util.ArrayList;
import java.util.List;

public class ChannelDao {

    private static final String TAG = ChannelDao.class.getSimpleName();

    public static void insertChannels(ContentResolver contentResolver, LongSparseArray<Channel> insertChannels, String inputId, String packageName) {
        List<ContentValues> contentValues = ConverterUtils.convertToContentValues(insertChannels, inputId, packageName);
        bulkInsert(contentResolver, contentValues, TvContract.Channels.CONTENT_URI);
    }

    public static void insertTifExtensionChannels(ContentResolver contentResolver, List<TifExtensionChannel> tifExtensionChannels) {
        List<ContentValues> tifExtensionContentValues = ConverterUtils.convertToTifExtensionContentValues(tifExtensionChannels);
        Log.d(TAG, "Received " + tifExtensionChannels.size() + " tif extension channels to be added");
        bulkInsert(contentResolver, tifExtensionContentValues, TifExtensionContract.Channels.CONTENT_URI);
    }

    public static void upsertChannels(ContentResolver contentResolver, LongSparseArray<Channel> insertChannels, LongSparseArray<Channel> updateChannels, String inputId, String packageName) {
        List<ContentValues> insertContentValues = ConverterUtils.convertToContentValues(insertChannels, inputId, packageName);
        List<ContentValues> updateContentValues = ConverterUtils.convertToContentValues(updateChannels, inputId, packageName);

        Log.d(TAG, "Received " + (insertContentValues.size() + updateContentValues.size()) + " channels to be upserted");
        ArrayList<ContentProviderOperation> bulkOperations = ConverterUtils.convertToInsertContentProviderOperation(insertContentValues);
        bulkOperations.addAll(ConverterUtils.convertToUpdateContentProviderOperation(updateContentValues));

        applyBulkOperations(contentResolver, bulkOperations, TvContract.AUTHORITY);
    }

    private static void bulkInsert(ContentResolver contentResolver, List<ContentValues> contentValues, Uri uri) {
        try {
            // Bulk insertion into desired table specified by Uri
            ContentValues[] insertBulk = contentValues.toArray(new ContentValues[0]);
            int rowsCreated = contentResolver.bulkInsert(uri, insertBulk);
            Log.d(TAG, "Successfully added " + rowsCreated + " channels to " + uri.toString());
        } catch (Exception e) {
            Log.e(TAG, "Exception in bulk inserting channels", e);
        }
    }

    private static void applyBulkOperations(ContentResolver contentResolver, ArrayList<ContentProviderOperation> bulkOperations, String authority) {
        if (bulkOperations.size() > 0) {
            try {
                ContentProviderResult[] results = contentResolver.applyBatch(
                        authority,
                        bulkOperations
                );
                Log.d(TAG, "Successfully applied bulk operation for " + results.length + " channels");
            } catch (Exception e) {
                Log.e(TAG, "Exception in applying bulk operation", e);
            }
        } else {
            Log.d(TAG, "No channels provided");
        }
    }

    public static void deleteChannels(ContentResolver contentResolver, List<Long> deletedChannelIds) {
        Log.d(TAG, "Received " + deletedChannelIds.size() + " channels to delete");
        applyBulkOperations(contentResolver, ConverterUtils.convertToDeleteContentProviderOperation(deletedChannelIds), TvContract.AUTHORITY);
    }

    public static void deleteAllChannels(ContentResolver contentResolver) {
        int rowsDeleted = contentResolver.delete(TvContract.Channels.CONTENT_URI, null, null);
        Log.d(TAG, "Deleted " + rowsDeleted + " channels from the DB");
    }

    public static List<Channel> getAllChannels(ContentResolver contentResolver) {
        List<Channel> channels = new ArrayList<>();
        // TvProvider returns programs in chronological order by default.
        try (Cursor cursor = contentResolver.query(TvContract.Channels.CONTENT_URI, Channel.PROJECTION, null, null, null)) {
            if (cursor == null) {
                Log.w(TAG, "Null cursor, TIF state is unknown");
                return null;
            }

            if (cursor.getCount() == 0) {
                Log.d(TAG, "No channels were found");
                return channels;
            }

            while (cursor.moveToNext()) {
                Channel channel = Channel.fromCursor(cursor);
                channels.add(channel);
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to get channels", e);
            e.printStackTrace();
            return null;
        }

        Log.d(TAG, "Retrieved " + channels.size() + " from the database");
        return channels;
    }

    public static void updateChannel(ContentResolver contentResolver, Channel updatedChannel, Long rowId, String inputId, String packageName) {
        ContentValues contentValues = updatedChannel.toContentValues(inputId, packageName);
        contentValues.put(TvContract.Channels._ID, rowId);
        contentResolver.update(TvContract.buildChannelUri(rowId), contentValues, null, null);
        Log.d(TAG, "Update channel with network id " + updatedChannel.getOriginalNetworkId());
    }

    /**
     * Returns the {@link Channel} with specified channel URI.
     *
     * @param channelUri URI of channel.
     * @return An channel object with specified channel URI.
     * @hide
     */
    public static Channel getChannel(ContentResolver contentResolver, Uri channelUri) {
        try (Cursor cursor = contentResolver.query(channelUri, Channel.PROJECTION, null, null, null)) {
            if (cursor == null || cursor.getCount() == 0) {
                Log.w(TAG, "No channel matches " + channelUri);
                return null;
            }
            cursor.moveToNext();
            return Channel.fromCursor(cursor);
        } catch (Exception e) {
            Log.w(TAG, "Unable to get the channel with URI " + channelUri, e);
            return null;
        }
    }
}
