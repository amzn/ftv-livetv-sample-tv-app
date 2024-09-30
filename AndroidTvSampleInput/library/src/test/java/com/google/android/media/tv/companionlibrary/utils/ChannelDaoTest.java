package com.google.android.media.tv.companionlibrary.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;

import com.google.android.media.tv.companionlibrary.model.Channel;
import com.google.android.media.tv.companionlibrary.model.TifExtension;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

public class ChannelDaoTest {

    private ContentResolver contentResolver;
    private List<TifExtensionChannel> channels;
    private final ArgumentCaptor<ContentValues[]> contentValuesArgumentCaptor = ArgumentCaptor.forClass(ContentValues[].class);
    private final ArgumentCaptor<Uri> uriArgumentCaptor = ArgumentCaptor.forClass(Uri.class);
    private Cursor cursor;

    @Before
    public void setup() {
        contentResolver = mock(ContentResolver.class);
        when(contentResolver.bulkInsert(any(Uri.class), any(ContentValues[].class)))
                .thenReturn(0);

        channels = new ArrayList<>();
        channels.add(new TifExtensionChannel
                .Builder()
                .setTifExtension(new TifExtension
                        .Builder()
                        .setGenre("Sports")
                        .build())
                .build()
        );

        cursor = mock(Cursor.class);
        when(cursor.moveToNext())
                .thenReturn(true)
                .thenReturn(false);
        when(cursor.isNull(anyInt()))
                .thenReturn(false);
        when(cursor.getLong(anyInt()))
                .thenReturn(1L);
        when(cursor.getString(anyInt()))
                .thenReturn("description")
                .thenReturn("displayName")
                .thenReturn("displayNumber")
                .thenReturn("inputId")
                .thenReturn("networkAffiliation")
                .thenReturn("packageName")
                .thenReturn("serviceType")
                .thenReturn("type")
                .thenReturn("videoFormat");
        when(cursor.getBlob(anyInt()))
                .thenReturn("test".getBytes());
        when(cursor.getInt(anyInt()))
                .thenReturn(2);

    }

    @Test
    public void testInsertChannels() {
        ChannelDao.insertTifExtensionChannels(contentResolver, channels);
        verify(contentResolver).bulkInsert(uriArgumentCaptor.capture(), contentValuesArgumentCaptor.capture());
        assertEquals(TifExtensionContract.Channels.CONTENT_URI, uriArgumentCaptor.getValue());
        assertEquals(1, contentValuesArgumentCaptor.getAllValues().get(0).length);
    }

    @Test
    public void testGetAllChannels() {
        when(contentResolver.query(eq(TvContract.Channels.CONTENT_URI), eq(Channel.PROJECTION), isNull(), isNull(), isNull()))
                .thenReturn(cursor);

        when(cursor.getCount())
                .thenReturn(1);

        List<Channel> channels = ChannelDao.getAllChannels(contentResolver);
        assertEquals(1, channels.size());
        Channel actualChannel = channels.get(0);
        assertEquals(1L, actualChannel.getId());
        assertEquals("description", actualChannel.getDescription());
        assertEquals("displayName", actualChannel.getDisplayName());
        assertEquals("displayNumber", actualChannel.getDisplayNumber());
        assertEquals("inputId", actualChannel.getInputId());
        assertEquals("test", new String(actualChannel.getInternalProviderDataByteArray()));
        assertEquals("networkAffiliation", actualChannel.getNetworkAffiliation());
        assertEquals(2, actualChannel.getOriginalNetworkId());
        assertEquals("packageName", actualChannel.getPackageName());
        assertFalse(actualChannel.isSearchable());
        assertEquals(2, actualChannel.getServiceId());
        assertEquals("serviceType", actualChannel.getServiceType());
        assertEquals(2, actualChannel.getTransportStreamId());
        assertEquals("type", actualChannel.getType());
        assertEquals("videoFormat", actualChannel.getVideoFormat());
    }

    @Test
    public void testGetAllChannelsNoResults() {
        when(cursor.getCount())
                .thenReturn(0);
        when(contentResolver.query(eq(TvContract.Channels.CONTENT_URI), eq(Channel.PROJECTION), isNull(), isNull(), isNull()))
                .thenReturn(cursor);

        List<Channel> channels = ChannelDao.getAllChannels(contentResolver);
        assertEquals(0, channels.size());

    }

    @Test
    public void testGetAllChannelsNullCursor() {
        when(contentResolver.query(eq(TvContract.Channels.CONTENT_URI), eq(Channel.PROJECTION), isNull(), isNull(), isNull()))
                .thenReturn(null);
        assertNull(ChannelDao.getAllChannels(contentResolver));
    }
}