package io.github.boomboom.ui.sound

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.boomboom.logic.AppServices
import io.github.boomboom.logic.EqualizerStore
import io.github.boomboom.logic.SoundSettings
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.BoomTheme

/**
 * The equaliser and amplifier, "Sound". Every change applies to the sound at once.
 *
 * The app shows it as a destination of its own over everything (full screen, not a draggable sheet): with no
 * pull-to-dismiss there is nothing for the faders' vertical drags to fight, and Done or the system Back close it,
 * which is how a full-screen settings page closes on Android.
 *
 * The header (Done, Reset, the master switch, "Original") stays put; the graph, faders and sections scroll under it.
 * Only the meter views read the store's live levels, so the rest recomposes only when a setting changes.
 *
 * @param scrollState The page's scroll, for tests that look at where it is.
 */
@Composable
fun SoundScreen(
    services: AppServices,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    val equalizer = services.equalizer
    val state by equalizer.state.collectAsState()
    val settings = state.settings
    // The band a finger is on, if any: lights its point on the graph and stops presets gliding under the finger.
    var activeBand by remember { mutableStateOf<Int?>(null) }
    var isConfirmingReset by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<Naming>(Naming.Closed) }

    BackHandler(onBack = onDone)
    DisposableEffect(equalizer) {
        equalizer.startMetering()
        onDispose {
            equalizer.setComparing(false)
            equalizer.stopMetering()
        }
    }

    BoomTheme {
        Box(modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(BoomColor.background)
                    .testTag("sound"),
            ) {
                SoundHeader(
                    isEnabled = settings.isEnabled, isComparing = state.isComparing, equalizer = equalizer, onDone = onDone,
                    onReset = { if (hasChanges(settings)) isConfirmingReset = true else equalizer.reset() },
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .navigationBarsPadding()
                        .padding(horizontal = BoomMetrics.margin)
                        .padding(bottom = 40.dp)
                        .testTag("sound.scroll"),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        // Flat while off: the sound then passes untouched, whatever the faders say.
                        val isLive = settings.isEnabled
                        // Worked out by the store's own arithmetic (the engine's, in the app), once per change of settings.
                        val curve = remember(settings) {
                            if (isLive) equalizer.response(GraphScale.frequencies).toList() else flatCurve
                        }
                        val points = remember(settings) {
                            if (isLive) equalizer.response(GraphScale.bandFrequencies).toList() else flatPoints
                        }
                        SoundGraph(
                            curve = curve, points = points, isEnabled = isLive, isComparing = state.isComparing,
                            glides = activeBand == null, meter = equalizer.meter, activeBand = activeBand,
                        )
                        SoundFaders(settings.gains, settings.isEnabled, equalizer, activeBand) { activeBand = it }
                    }
                    SoundPresets(state.presets, state.favoritePresetIds, state.selectedPresetId, equalizer) { naming = it }
                    SoundToneSection(settings.bass, settings.treble, settings.isEnabled, equalizer)
                    SoundAmplifierSection(
                        boost = settings.boost, preamp = settings.preamp, headroom = equalizer.headroom,
                        isEnabled = settings.isEnabled, isLimiterOn = settings.isLimiterEnabled,
                        isAutoHeadroomOn = settings.isAutoHeadroomEnabled, isNormalising = settings.isLoudnessNormalizationEnabled,
                        equalizer = equalizer,
                    )
                    SoundEnhanceSection(settings.deepBass, settings.air, settings.isEnabled, equalizer)
                    SoundStereoSection(
                        width = settings.width, balance = settings.balance, crossfeed = settings.crossfeed,
                        isSpatial = settings.isSpatialEnabled, isHeadTracking = settings.isHeadTrackingEnabled,
                        isHeadTrackingAvailable = state.isHeadTrackingAvailable, isEnabled = settings.isEnabled,
                        equalizer = equalizer,
                    )
                    SoundOutputSection(equalizer.meter)
                }
            }
            PresetNamingPrompt(naming, state.presets, equalizer) { naming = Naming.Closed }
        }
        if (isConfirmingReset) {
            AlertDialog(
                onDismissRequest = { isConfirmingReset = false },
                containerColor = BoomColor.surfaceRaised,
                title = { Text("Reset the sound?", style = BoomFont.sectionTitle) },
                text = {
                    Text(
                        "Bands, tone, amplifier, enhance, stereo and Spatial go back to their defaults. The master switch " +
                            "and loudness normalising stay as they are.",
                        style = BoomFont.itemSubtitle, color = BoomColor.textSecondary,
                    )
                },
                confirmButton = {
                    TextButton(
                        {
                            isConfirmingReset = false
                            equalizer.reset()
                        },
                        Modifier.testTag("sound.reset.confirm"),
                    ) { Text("Reset everything", color = BoomColor.destructive, style = BoomFont.button) }
                },
                dismissButton = {
                    TextButton({ isConfirmingReset = false }, Modifier.testTag("sound.reset.cancel")) {
                        Text("Cancel", color = BoomColor.textPrimary, style = BoomFont.button)
                    }
                },
                modifier = Modifier.testTag("sound.reset.dialog"),
            )
        }
    }
}

private val flatCurve = List(GraphScale.frequencies.size) { 0.0 }
private val flatPoints = List(EqualizerStore.bands.size) { 0.0 }

/** True when Reset would undo something worth asking about. */
internal fun hasChanges(settings: SoundSettings): Boolean =
    settings != SoundSettings(isEnabled = settings.isEnabled, isLoudnessNormalizationEnabled = settings.isLoudnessNormalizationEnabled)

/** Done, the title and Reset; under them the master switch and the press-and-hold "Original" button. */
@Composable
private fun SoundHeader(
    isEnabled: Boolean,
    isComparing: Boolean,
    equalizer: EqualizerStore,
    onDone: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(BoomColor.background)
            .statusBarsPadding()
            .padding(horizontal = BoomMetrics.margin)
            .padding(top = 4.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = BoomMetrics.minimumTarget),
        ) {
            HeaderButton("Done", BoomColor.accent, FontWeight.SemiBold, "sound.done", Modifier.align(Alignment.CenterStart), onDone)
            Text(
                "Sound", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = BoomColor.textPrimary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .semantics { heading() },
            )
            HeaderButton("Reset", BoomColor.textPrimary, FontWeight.Normal, "sound.reset", Modifier.align(Alignment.CenterEnd), onReset)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MasterSwitch(isEnabled, equalizer, Modifier.weight(1f))
            OriginalButton(isComparing, isEnabled, equalizer)
        }
    }
    HorizontalDivider(thickness = 0.5.dp, color = BoomColor.separator)
}

@Composable
private fun HeaderButton(text: String, color: Color, weight: FontWeight, tag: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = BoomMetrics.minimumTarget)
            .widthIn(min = BoomMetrics.minimumTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 17.sp, fontWeight = weight, color = color, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
private fun MasterSwitch(isOn: Boolean, equalizer: EqualizerStore, modifier: Modifier) {
    val haptics = rememberSoundHaptics()
    val border by animateColorAsState(if (isOn) BoomColor.accent.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.06f), label = "border")
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxHeight()
            .background(BoomColor.surface, shape)
            .border(1.dp, border, shape)
            .toggleable(isOn, role = Role.Switch) {
                haptics.toggle(it)
                equalizer.setEnabled(it)
            }
            .semantics { contentDescription = "Equaliser" }
            .testTag("sound.master")
            .padding(start = 8.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(30.dp)
                .then(if (isOn) Modifier.shadow(6.dp, CircleShape, ambientColor = BoomColor.accent, spotColor = BoomColor.accent) else Modifier)
                .background(if (isOn) BoomColor.accent else BoomColor.surfaceRaised, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.PowerSettingsNew, null, tint = if (isOn) BoomColor.onAccent else BoomColor.textSecondary,
                modifier = Modifier.size(17.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text("Equaliser", style = BoomFont.button, color = BoomColor.textPrimary, maxLines = 1)
            Text(
                if (isOn) "On" else "Off: untouched", style = BoomFont.caption.copy(fontWeight = FontWeight.SemiBold),
                color = if (isOn) BoomColor.accent else BoomColor.textTertiary, maxLines = 1,
            )
        }
        SoundSwitchKnob(isOn)
    }
}

/** Press and hold to hear the song as it was made; let go to hear the settings again. */
@Composable
private fun OriginalButton(isComparing: Boolean, isEnabled: Boolean, equalizer: EqualizerStore) {
    val haptics = rememberSoundHaptics()
    val scale by animateFloatAsState(if (isComparing) 0.96f else 1f, tween(150), label = "press")
    val shape = RoundedCornerShape(14.dp)
    val foreground = if (isComparing) BoomColor.onAccent else BoomColor.textPrimary
    Column(
        Modifier
            .width(132.dp)
            .fillMaxHeight()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .alpha(if (isEnabled) 1f else 0.5f)
            .background(if (isComparing) BoomColor.accent else BoomColor.surface, shape)
            .border(1.dp, if (isComparing) Color.Transparent else Color.White.copy(alpha = 0.12f), shape)
            // The finger is read on the button itself, from touch-down to lift: nothing scrolls while it is held.
            .soundTouch(FingerMode.IMMEDIATE) { event ->
                when (event) {
                    is FingerEvent.Began -> {
                        haptics.tick()
                        equalizer.setComparing(true)
                    }
                    is FingerEvent.Ended, FingerEvent.Cancelled -> equalizer.setComparing(false)
                    is FingerEvent.Moved -> Unit
                }
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "Original"
                stateDescription = if (isComparing) "Hearing the original" else "Off"
                customActions = listOf(
                    CustomAccessibilityAction(if (isComparing) "Hear the settings" else "Hear the original") {
                        equalizer.setComparing(!equalizer.isComparing)
                        true
                    },
                )
            }
            .testTag("sound.original")
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isComparing) Icons.Filled.Hearing else Icons.Outlined.Hearing, null, tint = foreground, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text("Original", style = BoomFont.button, color = foreground, maxLines = 1)
        }
        Text(
            if (isComparing) "Hearing original" else "Hold to compare",
            fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, color = foreground.copy(alpha = 0.8f),
            textAlign = TextAlign.Center, maxLines = 2,
        )
    }
}
