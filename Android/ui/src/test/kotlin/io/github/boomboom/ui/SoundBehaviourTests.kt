package io.github.boomboom.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.github.boomboom.logic.AppServices
import io.github.boomboom.logic.EqualizerStore
import io.github.boomboom.logic.KotlinSoundMath
import io.github.boomboom.logic.SoundSettings
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Sound screen under a finger: real pointer input (down, move, up) on the controls themselves, checked against
 * the store and against the page's scroll position.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class SoundBehaviourTests : PlayerTest() {
    private lateinit var services: AppServices
    private val equalizer get() = services.equalizer
    private val gains get() = equalizer.settings.gains

    private fun open(setUp: (EqualizerStore) -> Unit = {}) {
        services = playerServices()
        setUp(services.equalizer)
        showSound(services)
    }

    /** The faders' travel in pixels: their box less a cap's height (16 dp). */
    private fun faderTravel(): Float {
        val height = compose.onNodeWithTag("sound.faders").fetchSemanticsNode().size.height
        return height - with(compose.density) { 16.dp.toPx() }
    }

    private fun faderY(decibels: Float): Float {
        val inset = with(compose.density) { 8.dp.toPx() }
        return inset + (12 - decibels) / 24 * faderTravel()
    }

    @Test
    fun aVerticalDragOnAFaderMovesThatBandFromWhereItIsAndTheScreenStaysPut() {
        open { it.setGain(6f, 3) }
        val scrollBefore = soundScroll.value
        val screenTop = compose.onNodeWithTag("sound").fetchSemanticsNode().positionInRoot.y
        // The finger lands on band 3 at the 0 dB line, well away from its cap at +6 dB, and moves up 40 pixels.
        compose.onNodeWithTag("sound.faders").performTouchInput {
            down(Offset(width * 3.5f / 10, faderY(0f)))
            repeat(5) { moveBy(Offset(0f, -8f)) }
            up()
        }
        val expected = 6 + 40 / faderTravel() * 24
        assertEquals(expected, gains[3], 0.11f)
        assertTrue("the band moved from +6, not to the finger", gains[3] > 6f)
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), gains.filterIndexed { index, _ -> index != 3 })
        assertEquals("the page did not scroll", scrollBefore, soundScroll.value)
        assertEquals("the screen did not move", screenTop, compose.onNodeWithTag("sound").fetchSemanticsNode().positionInRoot.y)
    }

    @Test
    fun crossingZeroOnAFaderHoldsAtTheDetentWithAHaptic() {
        open { it.setGain(1f, 2) }
        compose.onNodeWithTag("sound.faders").performTouchInput {
            down(Offset(width * 2.5f / 10, faderY(-6f)))
            // Down by about 1.1 dB: from +1 to about −0.1, inside the pull to 0.
            moveBy(Offset(0f, 1.1f / 24 * faderTravel()))
            up()
        }
        assertEquals(0f, gains[2])
        assertTrue("a detent haptic was asked for", HapticFeedbackType.SegmentTick in haptics.played)
    }

    @Test
    fun aSidewaysStrokeAcrossTheFadersDrawsACurve() {
        open()
        compose.onNodeWithTag("sound.faders").performTouchInput {
            val y = faderY(6f)
            down(Offset(width * 1.5f / 10, y))
            // Half a column at a time, to band 6.
            repeat(10) { moveBy(Offset(width / 20f, 0f)) }
            up()
        }
        for (band in 1..6) assertEquals("band $band", 6f, gains[band], 0.15f)
        for (band in listOf(0, 7, 8, 9)) assertEquals("band $band", 0f, gains[band])
        assertTrue("each new band ticks", haptics.played.count { it == HapticFeedbackType.SegmentFrequentTick } >= 5)
    }

    @Test
    fun aDoubleTapOnAFaderZeroesIt() {
        open { it.setGain(4f, 5) }
        compose.onNodeWithTag("sound.faders").performTouchInput { doubleClick(Offset(width * 5.5f / 10, faderY(-9f))) }
        assertEquals(0f, gains[5])
    }

    @Test
    fun aSidewaysDragOnASliderMovesItFromWhereItIsAndDoesNotScroll() {
        open { it.setBass(-3f) }
        scrollSoundTo("sound.section.tone")
        val scrollBefore = soundScroll.value
        val usable = compose.onNodeWithTag("sound.bass").fetchSemanticsNode().size.width - with(compose.density) { 24.dp.toPx() }
        compose.onNodeWithTag("sound.bass").performTouchInput {
            // Far from the thumb: the value must move by the drag, not jump to the finger.
            down(Offset(width * 0.85f, height * 0.6f))
            repeat(5) { moveBy(Offset(20f, 0f)) }
            up()
        }
        assertEquals(-3f + 100 / usable * 24, equalizer.settings.bass, 0.11f)
        assertEquals("the page did not scroll", scrollBefore, soundScroll.value)
    }

    @Test
    fun aVerticalDragStartingOnASliderScrollsThePageAndLeavesTheValue() {
        open { it.setBass(-3f) }
        scrollSoundTo("sound.section.tone")
        val scrollBefore = soundScroll.value
        compose.onNodeWithTag("sound.bass").performTouchInput {
            down(Offset(width * 0.5f, height * 0.6f))
            repeat(8) { moveBy(Offset(2f, -25f)) }
            up()
        }
        assertEquals(-3f, equalizer.settings.bass)
        assertTrue("the page scrolled (${soundScroll.value} from $scrollBefore)", soundScroll.value > scrollBefore + 100)
    }

    @Test
    fun aDoubleTapOnASliderReturnsItToItsDefault() {
        open { it.setWidth(1.6f) }
        scrollSoundTo("sound.section.stereo")
        compose.onNodeWithTag("sound.width").performTouchInput { doubleClick(Offset(width * 0.2f, height * 0.6f)) }
        assertEquals(1f, equalizer.settings.width)
    }

    @Test
    fun aPresetChipAppliesItsValues() {
        open()
        scrollSoundTo("sound.section.presets")
        compose.onNodeWithTag("sound.preset.bass-boost").performClick()
        val preset = EqualizerStore.builtInPresets.first { it.id == "bass-boost" }
        assertEquals(preset.gains, gains)
        assertEquals("bass-boost", equalizer.selectedPresetId)
        assertTrue(equalizer.settings.isEnabled)
    }

    @Test
    fun starringMovesAPresetToTheFrontAndSurvivesReopeningTheScreen() {
        open { it.select(EqualizerStore.builtInPresets.first { preset -> preset.id == "pop" }) }
        scrollSoundTo("sound.section.presets")
        compose.onNodeWithTag("sound.preset.star").performClick()
        assertEquals(listOf("pop"), equalizer.favoritePresetIds)
        fun x(id: String) = compose.onNodeWithTag("sound.preset.$id").fetchSemanticsNode().positionInRoot.x
        assertTrue("Pop is before Flat", x("pop") < x("flat"))

        // Closed and opened again: still first.
        isSoundShown.value = false
        compose.waitForIdle()
        compose.onNodeWithTag("sound").assertDoesNotExist()
        isSoundShown.value = true
        compose.waitForIdle()
        scrollSoundTo("sound.section.presets")
        assertTrue("Pop is still before Flat", x("pop") < x("flat"))
        // And the app's next start reads it back from storage.
        val again = EqualizerStore(KotlinSoundMath, storage, CoroutineScope(dispatcher))
        assertEquals(listOf("pop"), again.favoritePresetIds)
    }

    @Test
    fun savingAPresetKeepsTheBoostAndChoosingItBringsTheBoostBack() {
        open {
            it.setGain(4f, 0)
            it.setBoost(6f)
        }
        scrollSoundTo("sound.section.presets")
        compose.onNodeWithTag("sound.preset.save").performClick()
        compose.onNodeWithTag("sound.preset.name").performTextInput("Loud night")
        compose.onNodeWithTag("sound.preset.name.save").performClick()
        compose.onNodeWithTag("sound.preset.naming").assertDoesNotExist()
        val saved = equalizer.presets.single { !it.isBuiltIn }
        assertEquals("Loud night", saved.name)
        assertEquals(saved.id, equalizer.selectedPresetId)

        compose.runOnIdle { equalizer.setBoost(2f) }
        assertEquals(null, equalizer.selectedPresetId)
        compose.onNode(hasText("Loud night") and hasClickAction()).performClick()
        assertEquals(6f, equalizer.settings.boost)
        assertEquals(4f, gains[0])
    }

    @Test
    fun everySwitchTogglesOnItsFirstTap() {
        sink.isHeadTrackingAvailable = true
        open { it.setSpatialEnabled(true) }
        val switches = listOf<Pair<String, (SoundSettings) -> Boolean>>(
            "sound.master" to { it.isEnabled },
            "sound.limiter" to { it.isLimiterEnabled },
            "sound.autoHeadroom" to { it.isAutoHeadroomEnabled },
            "sound.normalise" to { it.isLoudnessNormalizationEnabled },
            "sound.spatial" to { it.isSpatialEnabled },
            "sound.headTracking" to { it.isHeadTrackingEnabled },
        )
        for ((tag, read) in switches) {
            if (tag == "sound.headTracking") {
                // Spatial was just turned off by its own tap: on again, so its sub-switch shows.
                compose.runOnIdle { equalizer.setSpatialEnabled(true) }
            }
            val before = read(equalizer.settings)
            val node = compose.onNodeWithTag(tag)
            // The master switch sits in the fixed header; the others scroll.
            if (tag != "sound.master") node.performScrollTo()
            node.performClick()
            assertNotEquals("$tag toggled on its first tap", before, read(equalizer.settings))
        }
    }

    @Test
    fun followHeadMovementIsShownDisabledWithItsReasonWhenTheHeadphonesCannotReport() {
        sink.isHeadTrackingAvailable = false
        open { it.setSpatialEnabled(true) }
        val node = compose.onNodeWithTag("sound.headTracking").performScrollTo()
        node.assertIsNotEnabled()
        compose.onNodeWithText("Needs headphones that report head movement to this phone.").assertExists()
        val before = equalizer.settings.isHeadTrackingEnabled
        node.performClick()
        assertEquals(before, equalizer.settings.isHeadTrackingEnabled)
        // Never named after another company's system.
        for (word in listOf("Atmos", "Dolby", "3D surround")) compose.onNodeWithText(word, substring = true).assertDoesNotExist()
    }

    @Test
    fun holdingOriginalComparesUntilTheFingerLifts() {
        open { it.setGain(5f, 1) }
        val scrollBefore = soundScroll.value
        val original = compose.onNodeWithTag("sound.original")
        original.performTouchInput { down(center) }
        assertTrue(equalizer.isComparing)
        original.assert(hasText("Hearing original"))
        // A finger moving while it holds does not scroll the page.
        original.performTouchInput { repeat(4) { moveBy(Offset(0f, -30f)) } }
        assertEquals(scrollBefore, soundScroll.value)
        assertTrue(equalizer.isComparing)
        original.performTouchInput { up() }
        assertFalse(equalizer.isComparing)
        original.assert(hasText("Hold to compare"))
        // The settings were never touched.
        assertEquals(5f, gains[1])
    }

    @Test
    fun resetAsksFirst() {
        open { it.setGain(3f, 4) }
        compose.onNodeWithTag("sound.reset").performClick()
        compose.onNodeWithTag("sound.reset.dialog").assertExists()
        assertEquals("nothing reset before the answer", 3f, gains[4])
        compose.onNodeWithTag("sound.reset.cancel").performClick()
        assertEquals(3f, gains[4])

        compose.onNodeWithTag("sound.reset").performClick()
        compose.onNodeWithTag("sound.reset.confirm").performClick()
        compose.onNodeWithTag("sound.reset.dialog").assertDoesNotExist()
        assertTrue(gains.all { it == 0f })
    }

    @Test
    fun doneAndBackClose() {
        open()
        compose.onNodeWithTag("sound.done").performClick()
        assertFalse(isSoundShown.value)
        compose.onNodeWithTag("sound").assertDoesNotExist()

        isSoundShown.value = true
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertFalse(isSoundShown.value)
    }

    @Test
    fun theMetersReadTheProcessorOnlyWhileTheScreenIsOpen() {
        open {
            it.setEnabled(true)
            it.setBoost(12f)
        }
        meter()
        assertTrue("the limiter reads as working", equalizer.meter.gainReduction > 3f)
        isSoundShown.value = false
        compose.waitForIdle()
        meter(300)
        assertEquals("metering stopped and rested", 0f, equalizer.meter.gainReduction)
    }
}
