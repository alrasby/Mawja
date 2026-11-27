package com.mawja.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaBrowser
import androidx.media3.session.LibraryResult
import androidx.media3.common.MediaItem
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mawja.player.data.radio.CarRadioBrowse
import com.mawja.player.data.radio.RadioFilters
import com.mawja.player.playback.PlaybackService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises the public library protocol, not the phone's Compose view. No network needed. */
@RunWith(AndroidJUnit4::class)
class CarRadioBrowserTest {
    @Test fun realServicePublishesAndResolvesAllRadioCategories() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connection = AtomicReference<ListenableFuture<MediaBrowser>>()
        instrumentation.runOnMainSync {
            connection.set(MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync())
        }
        val browser = connection.get().get(15, TimeUnit.SECONDS)
        try {
            val children = AtomicReference<ListenableFuture<LibraryResult<ImmutableList<MediaItem>>>>()
            instrumentation.runOnMainSync { children.set(browser.getChildren("radio", 0, 100, null)) }
            val result = children.get().get(10, TimeUnit.SECONDS)
            val items = requireNotNull(result.value)
            assertEquals(RadioFilters.all.map(CarRadioBrowse::id), items.map { it.mediaId })
            items.forEach { item ->
                assertEquals(true, item.mediaMetadata.isBrowsable)
                assertEquals(false, item.mediaMetadata.isPlayable)
                assertTrue(item.mediaMetadata.title?.isNotBlank() == true)
                val lookup = AtomicReference<ListenableFuture<LibraryResult<MediaItem>>>()
                instrumentation.runOnMainSync { lookup.set(browser.getItem(item.mediaId)) }
                val resolved = requireNotNull(lookup.get().get(10, TimeUnit.SECONDS).value)
                assertEquals(item.mediaId, resolved.mediaId)
                assertEquals(item.mediaMetadata.title.toString(), resolved.mediaMetadata.title.toString())
            }
            assertFalse(items.any { it.mediaId == "radio:tv_audio" })
            val legacy = AtomicReference<ListenableFuture<LibraryResult<MediaItem>>>()
            instrumentation.runOnMainSync { legacy.set(browser.getItem("radio:tv_audio")) }
            val legacyItem = requireNotNull(legacy.get().get(10, TimeUnit.SECONDS).value)
            assertEquals("radio:tv_audio", legacyItem.mediaId)
            assertEquals(true, legacyItem.mediaMetadata.isBrowsable)
            assertEquals(items.single { it.mediaId == "radio:news" }.mediaMetadata.title.toString(),
                legacyItem.mediaMetadata.title.toString())
            val languageFolder = CarRadioBrowse.languagesId(RadioFilters.news, "AE")
            val languages = AtomicReference<ListenableFuture<LibraryResult<ImmutableList<MediaItem>>>>()
            instrumentation.runOnMainSync { languages.set(browser.getChildren(languageFolder, 0, 300, null)) }
            val choices = requireNotNull(languages.get().get(10, TimeUnit.SECONDS).value)
            assertTrue(choices.any { it.mediaId == CarRadioBrowse.choiceId(RadioFilters.news, "AE", "arabic") })
            assertTrue(choices.any { it.mediaId == CarRadioBrowse.choiceId(RadioFilters.news, "AE", null) })
            choices.forEach { choice ->
                assertEquals(true, choice.mediaMetadata.isBrowsable)
                assertEquals(false, choice.mediaMetadata.isPlayable)
            }
            val lookup = AtomicReference<ListenableFuture<LibraryResult<MediaItem>>>()
            instrumentation.runOnMainSync { lookup.set(browser.getItem(languageFolder)) }
            assertEquals(languageFolder, requireNotNull(lookup.get().get(10, TimeUnit.SECONDS).value).mediaId)
        } finally { instrumentation.runOnMainSync { browser.release() } }
    }
}
