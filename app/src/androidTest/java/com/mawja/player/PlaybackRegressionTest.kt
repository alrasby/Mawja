package com.mawja.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mawja.player.playback.PlaybackService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PlaybackRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @Test(timeout = 90000) fun localPlaybackSeekPauseAndRapidAudioSettingsRemainResponsive() {
        // Android 15+ rejects audio focus for background-only instrumentation.
        // Match the real user flow: open the app before starting playback.
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "playback-regression.wav")
        val samples = 16000 * 12
        DataOutputStream(file.outputStream()).use { out ->
            fun int(value: Int) = out.writeInt(Integer.reverseBytes(value))
            fun short(value: Int) = out.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt())
            out.writeBytes("RIFF"); int(36 + samples * 2); out.writeBytes("WAVEfmt "); int(16)
            short(1); short(1); int(16000); int(32000); short(2); short(16)
            out.writeBytes("data"); int(samples * 2)
            repeat(samples) { short((kotlin.math.sin(it * 2.0 * Math.PI * 440 / 16000) * 5000).toInt()) }
        }
        val future = AtomicReference<com.google.common.util.concurrent.ListenableFuture<MediaController>>()
        main { future.set(MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()) }
        val controller = future.get().get(15, TimeUnit.SECONDS)
        val prefs = (context.applicationContext as MawjaApp).container.preferences
        val oldPreset = prefs.equalizerPreset.value
        val oldSaver = prefs.batterySaver.value
        try {
            main {
                controller.setMediaItem(MediaItem.Builder().setMediaId("local:regression").setUri(android.net.Uri.fromFile(file)).build())
                controller.prepare(); controller.play()
            }
            val ready = AtomicReference(false)
            val deadline = System.currentTimeMillis() + 15000
            while (!ready.get() && System.currentTimeMillis() < deadline) {
                main { ready.set(controller.playbackState == Player.STATE_READY && controller.isPlaying) }
                Thread.sleep(100)
            }
            val details = AtomicReference("")
            main { details.set("state=${controller.playbackState}, playing=${controller.isPlaying}, requested=${controller.playWhenReady}, suppression=${controller.playbackSuppressionReason}, error=${controller.playerError?.errorCodeName}: ${controller.playerError?.cause}") }
            assertTrue("Playback never became ready: ${details.get()}", ready.get())
            main { assertNull(controller.playerError); assertTrue(controller.duration >= 11000); controller.seekTo(4000) }
            Thread.sleep(300)
            main { assertTrue(controller.currentPosition >= 3500); controller.pause(); assertFalse(controller.playWhenReady) }
            repeat(8) { i ->
                main {
                    prefs.setEqualizerPreset(listOf("flat", "bass", "vocal", "treble")[i % 4])
                    prefs.setBatterySaver(i % 2 == 0)
                    controller.volume = (i + 1) / 8f
                }
                Thread.sleep(40)
            }
            main { controller.play() }
            Thread.sleep(500)
            main { assertTrue(controller.isPlaying); assertNull(controller.playerError); assertEquals("treble", prefs.equalizerPreset.value) }
            // Probe actual publisher streams through the production service and HLS pipeline.
            val catalog = org.json.JSONArray(context.assets.open("radio/reviewed-stations.json").bufferedReader().use { it.readText() })
            val report = File(context.filesDir, "tv-audio-playback.txt")
            report.writeText("")
            for (index in 0 until catalog.length()) {
                val channel = catalog.getJSONObject(index)
                if (!channel.optBoolean("isTvAudio")) continue
                val id = "radio:official-" + channel.getString("id")
                main {
                    controller.setMediaItem(MediaItem.Builder().setMediaId(id).setUri(channel.getString("streamUrl")).build())
                    controller.prepare(); controller.play()
                }
                val liveReady = AtomicReference(false)
                val failed = AtomicReference(false)
                val liveDeadline = System.currentTimeMillis() + 22000
                while (!liveReady.get() && !failed.get() && System.currentTimeMillis() < liveDeadline) {
                    main {
                        liveReady.set(controller.playbackState == Player.STATE_READY && controller.isPlaying)
                        failed.set(controller.playerError != null)
                    }
                    Thread.sleep(100)
                }
                main {
                    val state = "$id ready=${liveReady.get()} error=${controller.playerError?.errorCodeName}: ${controller.playerError?.message}: ${controller.playerError?.cause}"
                    report.appendText(state + "\n")
                    assertTrue(state, liveReady.get())
                    assertFalse("Video selected for $id", controller.currentTracks.groups.any {
                        it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO && it.isSelected
                    })
                    assertNull(controller.playerError)
                    controller.pause()
                }
            }

        } finally {
            main { prefs.setEqualizerPreset(oldPreset); prefs.setBatterySaver(oldSaver); controller.stop(); controller.clearMediaItems(); controller.release() }
            file.delete()
            scenario.close()
        }
    }
}
