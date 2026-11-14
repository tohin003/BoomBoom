package io.github.boomboom.ui

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.boomboom.logic.PlaybackStatus
import io.github.boomboom.ui.preview.PreviewMusicAPI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The mini player, the full player, its panels and its menu, recorded as pictures. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class PlayerScreenshots : PlayerTest() {
    private val api = PreviewMusicAPI(delays = false)
    private val album get() = api.catalog.tracksByAlbum.values.first { it.size >= 8 }

    @Test
    fun miniBarPlaying() {
        val services = playerServices(api)
        showPlayerApp(services)
        playList(services, album, startAt = 2)
        advance(61_000)
        assertEquals(PlaybackStatus.Playing, services.player.status)
        shot("miniplayer_playing")
    }

    @Test
    fun miniBarPaused() {
        val services = playerServices(api)
        showPlayerApp(services)
        playList(services, album, startAt = 2)
        advance(95_000)
        compose.onNodeWithTag("miniplayer.playPause").performClick()
        assertEquals(PlaybackStatus.Paused, services.player.status)
        shot("miniplayer_paused")
    }

    @Test
    fun miniBarLoading() {
        val services = playerServices(api)
        showPlayerApp(services)
        compose.runOnIdle { services.player.play(album, 1) }
        compose.waitForIdle()
        assertEquals(PlaybackStatus.Loading, services.player.status)
        shot("miniplayer_loading")
    }

    @Test
    fun miniBarFailed() {
        val services = playerServices(api)
        engine.unplayable = setOf(album[1].videoId)
        showPlayerApp(services)
        playList(services, album, startAt = 1)
        assertTrue(services.player.status is PlaybackStatus.Failed)
        shot("miniplayer_failed")
    }

    @Test
    fun playerPlaying() {
        val services = playerServices(api)
        showPlayerApp(services)
        playList(services, album, startAt = 2)
        advance(47_000)
        expand()
        shot("player_playing")
    }

    @Test
    fun playerLongTitle() {
        val services = playerServices(api)
        showPlayerApp(services)
        val long = album.mapIndexed { index, song ->
            if (index == 0) song.copy(title = "The Long Way Round Through Every Town We Never Meant to Leave (Extended Night Mix)") else song
        }
        playList(services, long)
        advance(12_000)
        expand()
        shot("player_long_title")
    }

    @Test
    @Config(qualifiers = PHONE, fontScale = 1.3f)
    fun playerLargeFont() {
        val services = playerServices(api)
        showPlayerApp(services)
        playList(services, album, startAt = 2)
        advance(47_000)
        expand()
        shot("player_font_1.3")
    }

    @Test
    fun playerFailed() {
        val services = playerServices(api)
        engine.unplayable = setOf(album[1].videoId)
        showPlayerApp(services)
        playList(services, album, startAt = 1)
        expand()
        shot("player_failed")
    }

    @Test
    fun panelQueue() {
        val services = playerServices(api)
        showPlayerApp(services)
        playList(services, album, startAt = 2)
        advance(20_000)
        expand()
        compose.onNodeWithTag("player.tab.upNext").performClick()
        shot("panel_queue")
    }

    @Test
    fun panelPlainLyrics() {
        val services = playerServices(PlainLyricsAPI(api))
        showPlayerApp(services)
        playList(services, album, startAt = 2)
        expand()
        compose.onNodeWithTag("player.tab.lyrics").performClick()
        compose.onNodeWithTag("lyrics.plain").assertExists()
        shot("panel_lyrics_plain")
    }

    @Test
    fun panelTimedLyricsMidSong() {
        val services = playerServices(api)
        showPlayerApp(services)
        playList(services, album, startAt = 2)
        expand()
        compose.onNodeWithTag("player.tab.lyrics").performClick()
        advance(47_000)
        compose.onNodeWithTag("lyrics.line.active").assertExists()
        shot("panel_lyrics_timed")
    }

    @Test
    fun panelRelated() {
        val services = playerServices(api)
        showPlayerApp(services)
        playList(services, album, startAt = 2)
        expand()
        compose.onNodeWithTag("player.tab.related").performClick()
        compose.onNodeWithTag("related.list").assertExists()
        shot("panel_related")
    }

    @Test
    fun playerMenu() {
        val services = playerServices(api)
        showPlayerApp(services)
        playList(services, album, startAt = 2)
        expand()
        compose.onNodeWithTag("player.more").performClick()
        shot("player_menu")
    }

    @Test
    fun sleepTimerChoices() {
        val services = playerServices(api)
        showPlayerApp(services)
        playList(services, album, startAt = 2)
        expand()
        compose.onNodeWithTag("player.more").performClick()
        compose.onNodeWithTag("player.menu.sleepTimer").performClick()
        shot("player_sleep_timer")
    }

    @Test
    fun sleepTimerSet() {
        val services = playerServices(api)
        showPlayerApp(services)
        playList(services, album, startAt = 2)
        expand()
        compose.onNodeWithTag("player.sleepTimer").performClick()
        compose.onNodeWithTag("player.sleep.15").performClick()
        advance(2_000)
        shot("player_sleep_timer_set")
    }
}
