package io.github.boomboom.ui

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import io.github.boomboom.logic.AppServices
import io.github.boomboom.ui.preview.PreviewMusicAPI
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Sound screen at each of its parts and in its telling states, recorded as pictures. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class SoundScreenshots : PlayerTest() {
    /** A sound worth looking at: Rock, a little boost, some deep bass, Spatial on. */
    private fun shaped(): AppServices {
        val services = playerServices(PreviewMusicAPI(delays = false))
        val equalizer = services.equalizer
        equalizer.select(io.github.boomboom.logic.EqualizerStore.builtInPresets.first { it.id == "rock" })
        equalizer.setBass(2.5f)
        equalizer.setBoost(3f)
        equalizer.setDeepBass(0.35f)
        equalizer.setWidth(1.2f)
        equalizer.setCrossfeed(0.25f)
        return services
    }

    @Test
    fun top() {
        showSound(shaped())
        meter()
        shot("sound_top")
    }

    @Test
    fun faders() {
        showSound(shaped())
        meter()
        scrollSoundTo("sound.fadersBlock")
        shot("sound_faders")
    }

    @Test
    fun amplifier() {
        showSound(shaped())
        meter()
        scrollSoundTo("sound.section.amplifier")
        shot("sound_amplifier")
    }

    @Test
    fun enhanceAndStereo() {
        val services = shaped()
        services.equalizer.setSpatialEnabled(true)
        showSound(services)
        meter()
        scrollSoundTo("sound.section.enhance")
        shot("sound_enhance_stereo")
    }

    @Test
    fun outputMeters() {
        showSound(shaped())
        meter()
        scrollSoundTo("sound.section.output")
        shot("sound_output")
    }

    @Test
    fun equaliserOff() {
        val services = shaped()
        services.equalizer.setEnabled(false)
        showSound(services)
        meter()
        shot("sound_off")
    }

    @Test
    fun originalHeld() {
        val services = shaped()
        showSound(services)
        meter()
        compose.onNodeWithTag("sound.original").performTouchInput { down(center) }
        meter(300)
        assertTrue(services.equalizer.isComparing)
        shot("sound_original_held")
        compose.onNodeWithTag("sound.original").performTouchInput { up() }
    }

    @Test
    fun limiterWorking() {
        val services = shaped()
        services.equalizer.setBoost(12f)
        sink.musicPeak = 0.8f
        showSound(services)
        meter()
        scrollSoundTo("sound.section.amplifier")
        shot("sound_limiter_working")
    }

    @Test
    fun clipWarning() {
        val services = shaped()
        services.equalizer.setBoost(9f)
        services.equalizer.setLimiterEnabled(false)
        sink.musicPeak = 0.8f
        showSound(services)
        meter()
        scrollSoundTo("sound.limiter")
        compose.onNodeWithTag("sound.clipWarning.clipping").assertExists()
        shot("sound_clip_warning")
    }

    @Test
    fun starredPresetFirst() {
        val services = shaped()
        services.equalizer.setFavorite(true, "jazz")
        services.equalizer.setFavorite(true, "late-night")
        services.equalizer.select(io.github.boomboom.logic.EqualizerStore.builtInPresets.first { it.id == "jazz" })
        showSound(services)
        meter()
        scrollSoundTo("sound.section.presets")
        shot("sound_starred_preset")
    }

    @Test
    fun headTrackingUnavailable() {
        val services = shaped()
        services.equalizer.setSpatialEnabled(true)
        showSound(services)
        meter()
        scrollSoundTo("sound.section.stereo")
        shot("sound_spatial_no_head_tracking")
    }

    @Test
    @Config(qualifiers = PHONE, fontScale = 1.3f)
    fun largeFont() {
        showSound(shaped())
        meter()
        shot("sound_font_1.3")
    }
}
