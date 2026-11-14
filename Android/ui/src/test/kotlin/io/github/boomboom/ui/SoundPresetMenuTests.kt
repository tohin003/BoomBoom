package io.github.boomboom.ui

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import io.github.boomboom.logic.AppServices
import io.github.boomboom.logic.EqualizerStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A preset chip's long-press menu: starring for every preset; renaming, updating and deleting for the listener's own. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class SoundPresetMenuTests : PlayerTest() {
    private lateinit var services: AppServices
    private val equalizer get() = services.equalizer
    private val own get() = equalizer.presets.filter { !it.isBuiltIn }

    /** The Sound screen with one preset of the listener's own, saved with a boost of 3 dB. */
    private fun open() {
        services = playerServices()
        services.equalizer.setBoost(3f)
        services.equalizer.saveCurrentAsPreset("Night drive")
        showSound(services)
        scrollSoundTo("sound.section.presets")
    }

    /** Brings the chip into the row's view (the selected one is scrolled to), then holds it. */
    private fun holdChip(name: String) {
        compose.onNodeWithTag("sound.presets").performScrollToNode(hasText(name) and hasClickAction())
        compose.onNode(hasText(name) and hasClickAction()).performTouchInput { longClick() }
    }

    @Test
    fun aBuiltInPresetOffersOnlyStarring() {
        open()
        holdChip("Pop")
        compose.onNodeWithTag("sound.preset.menu").assertExists()
        compose.onNodeWithTag("sound.preset.menu.rename").assertDoesNotExist()
        compose.onNodeWithTag("sound.preset.menu.update").assertDoesNotExist()
        compose.onNodeWithTag("sound.preset.menu.delete").assertDoesNotExist()
        compose.onNodeWithTag("sound.preset.menu.favourite").performClick()
        assertEquals(listOf("pop"), equalizer.favoritePresetIds)
        compose.onNodeWithTag("sound.preset.menu").assertDoesNotExist()
    }

    @Test
    fun renameAsksForTheNameStartingFromTheOldOne() {
        open()
        holdChip("Night drive")
        compose.onNodeWithTag("sound.preset.menu.rename").performClick()
        compose.onNodeWithTag("sound.preset.naming").assertExists()
        compose.onNodeWithTag("sound.preset.name").assert(hasText("Night drive"))
        compose.onNodeWithTag("sound.preset.name").performTextReplacement("Late drive")
        compose.onNodeWithTag("sound.preset.name.save").performClick()
        compose.onNodeWithTag("sound.preset.naming").assertDoesNotExist()
        assertEquals(listOf("Late drive"), own.map { it.name })
    }

    @Test
    fun updateKeepsTheSoundAsItIsNow() {
        open()
        compose.runOnIdle { equalizer.setBoost(5f) }
        holdChip("Night drive")
        compose.onNodeWithTag("sound.preset.menu.update").performClick()
        assertEquals(5f, own.single().sound?.boost)
        assertEquals(listOf("Night drive"), own.map { it.name })
    }

    @Test
    fun deleteRemovesTheListenersOwnPreset() {
        open()
        assertEquals(1, own.size)
        holdChip("Night drive")
        compose.onNodeWithTag("sound.preset.menu.delete").performClick()
        assertTrue(own.isEmpty())
        assertEquals(EqualizerStore.builtInPresets.size, equalizer.presets.size)
    }
}
