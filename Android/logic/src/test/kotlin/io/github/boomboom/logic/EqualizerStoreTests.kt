package io.github.boomboom.logic

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stands in for the audio engine: remembers what it was told and reports the levels it is given. */
private class RecordingSink : SoundProcessing {
    var last: SoundSettings? = null
    var levels = SoundLevels()

    override fun apply(settings: SoundSettings) { last = settings }
    override fun takeLevels(): SoundLevels = levels
    override fun spectrum(bands: Int): FloatArray = FloatArray(bands) { -30f }
    override var isHeadTrackingAvailable = false
}

private fun TestScope.makeStore(
    storage: KeyValueStore = InMemoryKeyValueStore(),
    sink: SoundProcessing? = null,
): EqualizerStore = EqualizerStore(KotlinSoundMath, storage, backgroundScope, virtualClock(), sink)

/** Float `<` and `==` the way the iPhone tests read them: plain numbers, not boxed ones. */
private fun near(actual: Double, expected: Double, tolerance: Double) = abs(actual - expected) < tolerance

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EqualizerStoreTests {
    @Test
    fun aBandBoostShowsInTheCurveAtItsFrequency() = runTest {
        val store = makeStore()
        store.setGain(6f, band = 5)
        assertTrue(store.settings.isEnabled)
        val curve = store.response(doubleArrayOf(1000.0, 100.0, 10_000.0))
        assertTrue(near(curve[0], 6.0, 0.05))
        assertTrue(abs(curve[1]) < 0.3)
        assertTrue(abs(curve[2]) < 0.3)
    }

    @Test
    fun offMeansFlat() = runTest {
        val store = makeStore()
        store.select(EqualizerStore.builtInPresets[1])
        store.setEnabled(false)
        assertTrue(store.response(doubleArrayOf(50.0, 1000.0)).all { it == 0.0 })
        assertTrue(store.settings.isTransparent)
    }

    @Test
    fun shelvesReachTheirGainAtTheExtremes() = runTest {
        val store = makeStore()
        store.setEnabled(true)
        store.setBass(9f)
        store.setTreble(4f)
        val curve = store.response(doubleArrayOf(20.0, 20_000.0, 1000.0))
        assertTrue(near(curve[0], 9.0, 0.5))
        assertTrue(near(curve[1], 4.0, 0.5))
        assertTrue(abs(curve[2]) < 0.6)
        // Auto headroom, when asked for, follows the highest point of the whole curve.
        assertEquals(0f, store.headroom)
        store.setAutoHeadroomEnabled(true)
        assertTrue(near(store.headroom.toDouble(), 9.0, 0.15))
        assertEquals(store.headroom, store.settings.profile(KotlinSoundMath).headroom)
        assertEquals(store.headroom, store.profile.headroom)
    }

    @Test
    fun tenBandsSetAlikeGiveALevelCurve() = runTest {
        val store = makeStore()
        for (band in 0 until 10) store.setGain(6f, band)
        val centres = EqualizerStore.bands.map { it.frequency }.toDoubleArray()
        assertTrue(store.response(centres).all { abs(it - 6) < 0.01 })
        val between = store.response(doubleArrayOf(45.0, 90.0, 177.0, 354.0, 707.0, 1414.0, 2828.0, 5657.0, 11_314.0))
        assertTrue(between.all { abs(it - 6) < 0.4 })
        store.setAutoHeadroomEnabled(true)
        assertTrue(store.headroom >= 6 && store.headroom < 6.3)
    }

    @Test
    fun comparingPlaysTheSongAsMadeAndLeavesTheSettingsAlone() = runTest {
        val sink = RecordingSink()
        val store = makeStore(sink = sink)
        store.setGain(6f, band = 2)
        store.setBoost(4f)
        assertEquals(true, sink.last?.isEnabled)
        store.setComparing(true)
        assertTrue(store.isComparing)
        assertEquals(false, sink.last?.isEnabled)
        assertTrue(store.settings.isEnabled && store.settings.boost == 4f)
        store.setComparing(false)
        assertEquals(store.settings, sink.last)
        // Touching a control ends the comparison and is heard at once.
        store.setComparing(true)
        store.setBoost(5f)
        assertFalse(store.isComparing)
        assertTrue(sink.last?.isEnabled == true && sink.last?.boost == 5f)
    }

    @Test
    fun settingsSavedByAnOlderVersionStillLoad() {
        val old = """{"preamp":2,"isLoudnessNormalizationEnabled":true,"gains":[1,0,0,0,0,0,0,0,0,3],"isAutoHeadroomEnabled":false,"boost":5,"bass":4,"treble":0,"isEnabled":true,"isLimiterEnabled":true}"""
        val settings = BoomJson.decodeFromString(SoundSettings.serializer(), old)
        assertTrue(settings.boost == 5f && settings.gains[9] == 3f && settings.isLoudnessNormalizationEnabled)
        assertTrue(settings.balance == 0f && settings.width == 1f)
    }

    @Test
    fun resetCentresAndFlattensButKeepsTheSwitch() = runTest {
        val store = makeStore()
        store.setGain(5f, band = 1)
        store.setBalance(-0.4f)
        store.setWidth(1.6f)
        store.setBoost(8f)
        store.reset()
        assertTrue(store.settings.isEnabled)
        assertTrue(store.settings.gains.all { it == 0f })
        assertTrue(store.settings.balance == 0f && store.settings.width == 1f && store.settings.boost == 0f)
        assertTrue(store.settings.isTransparent)
    }

    @Test
    fun metersFollowTheEngine() = runTest {
        val sink = RecordingSink()
        sink.levels = SoundLevels(peakLeft = 0.5f, peakRight = 0.25f, rmsLeft = 0.25f, gainReduction = 3f)
        val store = makeStore(sink = sink)
        store.startMetering()
        assertTrue(eventually { store.meter.left > -7 })
        assertTrue(abs(store.meter.left + 6.02) < 0.1)
        assertTrue(abs(store.meter.right + 12.04) < 0.1)
        assertTrue(abs(store.meter.rmsLeft + 12.04) < 0.1)
        assertTrue(store.meter.gainReduction > 2.5)
        assertEquals(LevelMeter.SPECTRUM_BANDS, store.meter.spectrum.size)
        assertEquals(-30f, store.meter.spectrum[10])
        store.stopMetering()
        assertTrue(store.meter.left == LevelMeter.FLOOR && store.meter.gainReduction == 0f)
    }

    @Test
    fun presetsAreRememberedAndCustomOnesCanGo() = runTest {
        val storage = InMemoryKeyValueStore()
        val first = makeStore(storage)
        first.setGain(3f, band = 0)
        val saved = first.saveCurrentAsPreset("  Mine ")
        assertEquals("Mine", saved.name)
        val second = makeStore(storage)
        assertEquals(3f, second.settings.gains[0])
        assertTrue(second.presets.any { it.id == saved.id })
        assertEquals(saved.id, second.selectedPresetId)
        second.deletePreset(saved.id)
        second.deletePreset("flat")
        assertFalse(second.presets.any { it.id == saved.id })
        assertTrue(second.presets.any { it.id == "flat" })
    }

    @Test
    fun aSavedPresetKeepsTheWholeSoundAndABuiltInOneOnlyTheBands() = runTest {
        val storage = InMemoryKeyValueStore()
        val store = makeStore(storage)
        store.setGain(4f, band = 1)
        store.setBoost(7f)
        store.setBass(3f)
        store.setDeepBass(0.5f)
        store.setWidth(1.4f)
        store.setLimiterEnabled(true)
        val mine = store.saveCurrentAsPreset("Car")
        assertTrue(mine.sound?.boost == 7f && mine.sound?.deepBass == 0.5f)
        assertEquals(mine.id, store.selectedPresetId)

        // A built-in preset changes the bands and leaves the amplifier where it is.
        val rock = EqualizerStore.builtInPresets.first { it.id == "rock" }
        store.select(rock)
        assertTrue(store.settings.gains == rock.gains && store.settings.boost == 7f && store.settings.width == 1.4f)
        assertEquals("rock", store.selectedPresetId)
        // Moving the boost does not unselect a preset that says nothing about the boost...
        store.setBoost(2f)
        assertEquals("rock", store.selectedPresetId)
        // ...but moving a band does.
        store.setGain(-3f, band = 4)
        assertNull(store.selectedPresetId)

        // The listener's own preset brings everything back, also after a restart.
        val later = makeStore(storage)
        val saved = later.presets.first { it.id == mine.id }
        later.select(saved)
        assertTrue(later.settings.gains[1] == 4f && later.settings.boost == 7f && later.settings.bass == 3f)
        assertTrue(later.settings.deepBass == 0.5f && later.settings.width == 1.4f)
        assertEquals(mine.id, later.selectedPresetId)
        // Here the boost is part of the preset, so changing it unselects.
        later.setBoost(9f)
        assertNull(later.selectedPresetId)
        // Saving over it keeps the new sound under the same name.
        later.overwritePreset(mine.id)
        assertEquals(9f, later.presets.first { it.id == mine.id }.sound?.boost)
        assertEquals(mine.id, later.selectedPresetId)
    }

    @Test
    fun favouritesAreRememberedAndGoWithADeletedPreset() = runTest {
        val storage = InMemoryKeyValueStore()
        val store = makeStore(storage)
        val mine = store.saveCurrentAsPreset("Mine")
        store.setFavorite(true, "jazz")
        store.setFavorite(true, mine.id)
        store.setFavorite(true, "no-such-preset")
        assertEquals(listOf("jazz", mine.id), store.favoritePresetIds)
        assertTrue(store.isFavorite("jazz") && !store.isFavorite("rock"))
        val later = makeStore(storage)
        assertEquals(listOf("jazz", mine.id), later.favoritePresets.map { it.id })
        later.deletePreset(mine.id)
        later.setFavorite(false, "jazz")
        assertTrue(later.favoritePresetIds.isEmpty())
        assertTrue(makeStore(storage).favoritePresetIds.isEmpty())
    }

    @Test
    fun aPresetSavedBeforeTheAmplifierWasKeptStillLoads() {
        val old = """[{"id":"custom-1","name":"Old","gains":[1,2,3,4,5,6,7,8,9,10],"isBuiltIn":false}]"""
        val presets = BoomJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(EqualizerPreset.serializer()), old,
        )
        assertNull(presets[0].sound)
        assertEquals(10f, presets[0].gains[9])
    }

    @Test
    fun settingsFromBeforeAutoHeadroomWasOptionalAreBroughtInLineOnce() = runTest {
        val storage = InMemoryKeyValueStore()
        val old = """{"preamp":0,"gains":[0,0,3,0,0,0,0,0,0,0],"isAutoHeadroomEnabled":true,"boost":0,"bass":0,"treble":0,"isEnabled":true,"isLimiterEnabled":true}"""
        storage.putString("sound.settings", old)
        val first = makeStore(storage)
        assertTrue(!first.settings.isAutoHeadroomEnabled && first.settings.gains[2] == 3f)
        // After that it is the listener's to decide, and the choice sticks.
        first.setAutoHeadroomEnabled(true)
        assertTrue(makeStore(storage).settings.isAutoHeadroomEnabled)
    }

    @Test
    fun spatialIsPartOfTheSoundAndOfASavedPreset() = runTest {
        val sink = RecordingSink()
        val store = makeStore(sink = sink)
        assertFalse(store.settings.wantsHeadTracking)
        store.setEnabled(true)
        store.setSpatialEnabled(true)
        assertEquals(true, sink.last?.profile(KotlinSoundMath)?.isSpatial)
        assertTrue(store.settings.wantsHeadTracking && !store.settings.isTransparent)
        val preset = store.saveCurrentAsPreset("Headphones")
        store.setSpatialEnabled(false)
        assertNull(store.selectedPresetId)
        store.select(preset)
        assertTrue(store.settings.isSpatialEnabled)
        store.setHeadTrackingEnabled(false)
        assertFalse(store.settings.wantsHeadTracking)
        // The headphones' ability is asked of the engine when a screen starts looking.
        sink.isHeadTrackingAvailable = true
        store.refreshHeadTrackingAvailability()
        assertTrue(store.isHeadTrackingAvailable)
    }

    @Test
    fun theHeadTrackerKeepsLoudspeakersInPlaceAndLetsThemFollowSlowly() {
        val turns = ArrayList<Float>()
        val tracker = HeadTracker { turns.add(it) }
        tracker.take(yaw = 1.0, atSeconds = 100.0)       // where the head points when it starts: straight ahead
        tracker.take(yaw = 1.5, atSeconds = 100.02)      // half a radian to the left, at once
        assertTrue(turns[0] == 0f)
        assertTrue(abs(turns[1] + 0.5f) < 0.01f)         // so the loudspeakers are half a radian to the right of the nose
        // Kept there, the loudspeakers come round: after half a minute they are in front again.
        var time = 100.02
        repeat(1500) {
            time += 0.02
            tracker.take(yaw = 1.5, atSeconds = time)
        }
        assertTrue(abs(turns.last()) < 0.02f)
        // Across the join of the circle the short way is taken.
        val wrapped = ArrayList<Float>()
        val other = HeadTracker { wrapped.add(it) }
        other.take(yaw = 3.1, atSeconds = 5.0)
        other.take(yaw = -3.1, atSeconds = 5.02)         // 0.083 radians further left, through pi
        assertTrue(abs(wrapped.last() + 0.083f) < 0.01f)
    }

    @Test
    fun valuesAreKeptInRange() = runTest {
        val store = makeStore()
        store.setGain(40f, band = 2)
        store.setBoost(-5f)
        store.setPreamp(-99f)
        store.setBass(-40f)
        store.setBalance(3f)
        store.setWidth(-1f)
        assertTrue(store.settings.gains[2] == 12f)
        assertTrue(store.settings.boost == 0f)
        assertTrue(store.settings.preamp == -12f)
        assertTrue(store.settings.bass == -12f)
        assertTrue(store.settings.balance == 1f)
        assertTrue(store.settings.width == 0f)
    }

    // Tests of my own.

    @Test
    fun theMeterFallsBackSteadilyHoldsItsPeakAndFlagsClipping() = runTest {
        val sink = RecordingSink()
        sink.levels = SoundLevels(peakLeft = 1.2f, peakRight = 0.5f)
        val store = makeStore(sink = sink)
        store.startMetering()
        assertTrue(eventually { store.meter.isClipping })
        assertTrue(store.meter.left > 1.5f && store.meter.holdLeft > 1.5f)
        // Silence follows: the bar falls at 26 dB a second, the hold mark waits one and a half seconds.
        sink.levels = SoundLevels()
        val peak = store.meter.left
        wait(1100)
        assertTrue(store.meter.left < peak - 20f, "left was ${store.meter.left}")
        assertTrue(store.meter.holdLeft > 1.5f, "the hold mark held")
        assertFalse(store.meter.isClipping, "the clip flag lasts a second")
        wait(1500)
        assertTrue(store.meter.holdLeft < 1f, "the hold mark let go: ${store.meter.holdLeft}")
        store.stopMetering()
    }

    @Test
    fun twoScreensMeterAtOnceAndTheLastToLeaveStopsIt() = runTest {
        val sink = RecordingSink()
        sink.levels = SoundLevels(peakLeft = 0.5f)
        val store = makeStore(sink = sink)
        store.startMetering()
        store.startMetering()
        assertTrue(eventually { store.meter.left > -7 })
        store.stopMetering()
        wait(100)
        assertTrue(store.meter.left > -7, "one screen is still looking")
        store.stopMetering()
        assertEquals(LevelMeter.FLOOR, store.meter.left)
    }

    @Test
    fun savedSettingsOfAnotherLengthAreFlattenedAndANewerVersionsFieldsAreIgnored() = runTest {
        val storage = InMemoryKeyValueStore()
        storage.putInt("sound.settingsVersion", 2)
        storage.putString("sound.settings", """{"isEnabled":true,"gains":[1,2,3],"fromTheFuture":{"a":1},"boost":null}""")
        val store = makeStore(storage)
        assertEquals(10, store.settings.gains.size)
        assertTrue(store.settings.gains.all { it == 0f })
        assertTrue(store.settings.isEnabled)
        assertEquals(0f, store.settings.boost)
        storage.putString("sound.settings", "this is not json")
        assertEquals(SoundSettings(), makeStore(storage).settings)
    }

    @Test
    fun theBandsAreLabelledAsOnTheIPhone() {
        assertEquals(
            listOf("32", "64", "125", "250", "500", "1K", "2K", "4K", "8K", "16K"), EqualizerStore.bands.map { it.label },
        )
        assertEquals(21, EqualizerStore.builtInPresets.size)
        assertTrue(EqualizerStore.builtInPresets.all { it.gains.size == 10 && it.isBuiltIn && it.sound == null })
    }

    @Test
    fun aPresetThatCarriesTheSoundMatchesOnlyThatSound() {
        val settings = SoundSettings(isEnabled = true, gains = List(10) { 1f }, boost = 3f)
        val preset = EqualizerPreset("p", "P", settings.gains, isBuiltIn = false, sound = EqualizerPreset.Sound.of(settings))
        assertTrue(preset.matches(settings))
        assertFalse(preset.matches(settings.copy(boost = 4f)))
        assertFalse(preset.matches(settings.copy(gains = List(10) { 2f })))
        // A preset without the sound ignores everything but the bands, and 0 and -0 are the same band.
        val bandsOnly = preset.copy(sound = null)
        assertTrue(bandsOnly.matches(settings.copy(boost = 4f)))
        assertTrue(EqualizerPreset("z", "Z", List(10) { 0f }, true).matches(SoundSettings(gains = List(10) { -0f })))
        assertNotNull(preset.sound)
    }
}
