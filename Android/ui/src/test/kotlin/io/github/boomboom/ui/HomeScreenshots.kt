package io.github.boomboom.ui

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import io.github.boomboom.ui.preview.PreviewMusicAPI
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class HomeScreenshots : ScreenTest() {
    @Test
    fun homeLoadedSignedIn() {
        showApp(services(PreviewMusicAPI(delays = false)))
        shot("home_loaded_signed_in")
    }

    @Test
    fun homeSignedOut() {
        showApp(services(PreviewMusicAPI(delays = false, signedIn = false), signedIn = false))
        shot("home_loaded_signed_out")
    }

    @Test
    fun homeLoading() {
        showApp(services(PreviewMusicAPI(delayOverride = { _, _ -> 10.minutes })))
        shot("home_loading")
    }

    @Test
    fun homeCouldNotLoad() {
        showApp(services(PreviewMusicAPI(delays = false, failsBrowsing = true)))
        shot("home_could_not_load")
    }

    @Test
    fun homeWithPlayerBar() {
        val api = PreviewMusicAPI(delays = false)
        val services = services(api)
        services.player.play(api.catalog.songs[3])
        showApp(services)
        shot("home_with_player_bar")
    }

    @Test
    @Config(qualifiers = PHONE, fontScale = 1.3f)
    fun homeLargeFont() {
        showApp(services(PreviewMusicAPI(delays = false)))
        shot("home_font_1.3")
    }

    @Test
    fun itemMenu() {
        showApp(services(PreviewMusicAPI(delays = false)))
        compose.onAllNodesWithTag("row.more").onFirst().performClick()
        shot("item_menu_song")
    }
}

/** A phone of 411 x 891 dp at 320 dpi (822 x 1782 pixels), in the dark. */
const val PHONE = "w411dp-h891dp-normal-long-notround-night-xhdpi-keyshidden-nonav"
