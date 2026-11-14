package io.github.boomboom.ui.sound

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.boomboom.logic.EqualizerPreset
import io.github.boomboom.logic.EqualizerStore
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics

// The sections under the faders. Each takes plain values (not the store's whole settings), so moving one control
// recomposes its own section and Compose skips the rest.

/** A section's small capitals title, with an optional note or control on the right. */
@Composable
internal fun SoundSectionTitle(title: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("sound.section.${title.lowercase()}")
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(), style = BoomFont.strapline.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
            color = BoomColor.textSecondary, modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.weight(1f).width(8.dp))
        trailing?.invoke()
    }
}

/** The rounded box each section's controls sit in. */
@Composable
internal fun SoundCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(BoomMetrics.cardRadius + 6.dp)
    Column(
        modifier
            .fillMaxWidth()
            .background(BoomColor.surface, shape)
            .border(1.dp, Color.White.copy(alpha = 0.05f), shape)
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) { content() }
}

@Composable
private fun CardDivider() {
    HorizontalDivider(thickness = 0.5.dp, color = BoomColor.separator)
}

@Composable
private fun SoundSection(title: String, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SoundSectionTitle(title, trailing)
        content()
    }
}

// Presets

/** The naming prompt, for a new preset or a rename. Changed only by the Sound screen. */
internal sealed class Naming {
    data object Closed : Naming()
    data object New : Naming()
    data class Rename(val id: String) : Naming()
}

/**
 * The preset chips: the starred ones first, then the rest in the store's order. The star beside the title stars the
 * selected preset; a long press on any chip offers starring, and for the listener's own presets renaming, updating
 * with the current sound and deleting. The listener's own presets carry the whole sound and say so.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SoundPresets(
    presets: List<EqualizerPreset>,
    favoriteIds: List<String>,
    selectedId: String?,
    equalizer: EqualizerStore,
    open: (Naming) -> Unit,
) {
    val haptics = rememberSoundHaptics()
    var menuFor by remember { mutableStateOf<String?>(null) }
    val starred = favoriteIds.toSet()
    val ordered = presets.filter { it.id in starred } + presets.filter { it.id !in starred }
    val selected = presets.firstOrNull { it.id == selectedId }
    val rowState = rememberLazyListState()

    // Keeps the selected chip in view (after choosing one elsewhere, starring, or saving a new one).
    val selectedIndex = ordered.indexOfFirst { it.id == selectedId }
    LaunchedEffect(selectedIndex, favoriteIds) {
        if (selectedIndex >= 0) rowState.animateScrollToItem(selectedIndex, -120)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Title and buttons on one line while they fit; with large text the buttons wrap under the title.
        FlowRow(
            Modifier
                .fillMaxWidth()
                .testTag("sound.section.presets")
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "PRESETS", style = BoomFont.strapline.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
                color = BoomColor.textSecondary,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .semantics { heading() },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected != null) {
                    val isStarred = selected.id in starred
                    TextAction(
                        if (isStarred) "Starred" else "Star", if (isStarred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        color = if (isStarred) BoomColor.accent else BoomColor.textPrimary,
                        label = if (isStarred) "Remove ${selected.name} from favourites" else "Add ${selected.name} to favourites",
                        tag = "sound.preset.star",
                    ) {
                        haptics.tick()
                        equalizer.setFavorite(!isStarred, selected.id)
                    }
                }
                TextAction("Save preset", Icons.Filled.AddCircle, BoomColor.accent, "Save as preset", "sound.preset.save") {
                    open(Naming.New)
                }
            }
        }
        LazyRow(
            state = rowState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 0.dp)
                .testTag("sound.presets"),
            contentPadding = PaddingValues(horizontal = 0.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ordered, key = { it.id }) { preset ->
                Box {
                    PresetChip(
                        preset, isSelected = preset.id == selectedId, isStarred = preset.id in starred,
                        modifier = Modifier.animateItem(),
                        onClick = {
                            haptics.tick()
                            equalizer.select(preset)
                        },
                        onLongClick = { menuFor = preset.id },
                    )
                    DropdownMenu(
                        expanded = menuFor == preset.id, onDismissRequest = { menuFor = null },
                        containerColor = BoomColor.surfaceRaised, modifier = Modifier.testTag("sound.preset.menu"),
                    ) {
                        val isStarred = preset.id in starred
                        DropdownMenuItem(
                            text = { Text(if (isStarred) "Remove from favourites" else "Add to favourites") },
                            leadingIcon = { Icon(if (isStarred) Icons.Outlined.StarBorder else Icons.Filled.Star, null) },
                            onClick = {
                                menuFor = null
                                equalizer.setFavorite(!isStarred, preset.id)
                            },
                            modifier = Modifier.testTag("sound.preset.menu.favourite"),
                        )
                        if (!preset.isBuiltIn) {
                            DropdownMenuItem(
                                text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                onClick = {
                                    menuFor = null
                                    open(Naming.Rename(preset.id))
                                },
                                modifier = Modifier.testTag("sound.preset.menu.rename"),
                            )
                            DropdownMenuItem(
                                text = { Text("Update with the current sound") }, leadingIcon = { Icon(Icons.Outlined.SaveAlt, null) },
                                onClick = {
                                    menuFor = null
                                    equalizer.overwritePreset(preset.id)
                                },
                                modifier = Modifier.testTag("sound.preset.menu.update"),
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = BoomColor.destructive) },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = BoomColor.destructive) },
                                onClick = {
                                    menuFor = null
                                    equalizer.deletePreset(preset.id)
                                },
                                modifier = Modifier.testTag("sound.preset.menu.delete"),
                            )
                        }
                    }
                }
            }
        }
        if (presets.any { !it.isBuiltIn }) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Tune, null, tint = BoomColor.textTertiary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Your own presets also set the tone, amplifier, enhance and stereo.", style = BoomFont.caption, color = BoomColor.textTertiary)
            }
        }
    }
}

/**
 * Asks for a preset's name, over the whole Sound screen and in its own window (a scrim and a card, as a dialog looks),
 * above the keyboard. Back or a tap outside cancels.
 */
@Composable
internal fun PresetNamingPrompt(naming: Naming, presets: List<EqualizerPreset>, equalizer: EqualizerStore, onClose: () -> Unit) {
    if (naming == Naming.Closed) return
    var name by remember(naming) {
        mutableStateOf((naming as? Naming.Rename)?.let { rename -> presets.firstOrNull { it.id == rename.id }?.name } ?: "")
    }
    fun finish(saving: Boolean) {
        if (saving) {
            when (naming) {
                Naming.New -> equalizer.saveCurrentAsPreset(name)
                is Naming.Rename -> equalizer.renamePreset(naming.id, name)
                Naming.Closed -> Unit
            }
        }
        onClose()
    }
    BackHandler { finish(saving = false) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(remember { MutableInteractionSource() }, indication = null) { finish(saving = false) }
            .imePadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .background(BoomColor.surfaceRaised, RoundedCornerShape(28.dp))
                // Taps on the card stay on the card.
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)
                .testTag("sound.preset.naming"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(if (naming == Naming.New) "Save as preset" else "Rename preset", style = BoomFont.messageTitle, color = BoomColor.textPrimary)
            if (naming == Naming.New) {
                Text(
                    "Keeps the whole sound as it is now: the ten bands, tone, amplifier, enhance and stereo.",
                    style = BoomFont.itemSubtitle, color = BoomColor.textSecondary,
                )
            }
            OutlinedTextField(
                name, { name = it }, singleLine = true, placeholder = { Text("Name") },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = BoomColor.accent, cursorColor = BoomColor.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sound.preset.name"),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton({ finish(saving = false) }) { Text("Cancel", color = BoomColor.textPrimary, style = BoomFont.button) }
                TextButton({ finish(saving = true) }, Modifier.testTag("sound.preset.name.save")) {
                    Text("Save", color = BoomColor.accent, style = BoomFont.button)
                }
            }
        }
    }
}

@Composable
private fun TextAction(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    label: String,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .heightIn(min = BoomMetrics.minimumTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = BoomFont.button, color = color, maxLines = 1, softWrap = false)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetChip(
    preset: EqualizerPreset,
    isSelected: Boolean,
    isStarred: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val foreground = if (isSelected) BoomColor.onAccent else BoomColor.textPrimary
    Box(
        modifier
            .heightIn(min = BoomMetrics.minimumTarget)
            .combinedClickable(role = Role.Button, onLongClickLabel = "More", onLongClick = onLongClick, onClick = onClick)
            .semantics {
                selected = isSelected
                if (isStarred) stateDescription = "Favourite"
            }
            .testTag("sound.preset.${preset.id}"),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(34.dp)
                .background(if (isSelected) BoomColor.accent else BoomColor.chip, CircleShape)
                .border(1.dp, if (isStarred && !isSelected) BoomColor.accent.copy(alpha = 0.35f) else Color.Transparent, CircleShape)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (isStarred) {
                Icon(Icons.Filled.Star, null, tint = if (isSelected) BoomColor.onAccent else BoomColor.accent, modifier = Modifier.size(12.dp))
            }
            Text(preset.name, style = BoomFont.button, color = foreground, maxLines = 1)
            if (preset.sound != null) Icon(Icons.Filled.Tune, null, tint = foreground.copy(alpha = 0.75f), modifier = Modifier.size(12.dp))
        }
    }
}

// Tone

@Composable
internal fun SoundToneSection(bass: Float, treble: Float, isEnabled: Boolean, equalizer: EqualizerStore) {
    SoundSection("Tone") {
        SoundCard {
            SoundSlider(SoundControl.BASS, bass, isEnabled, equalizer, detail = "Everything below about 100 Hz.")
            CardDivider()
            SoundSlider(SoundControl.TREBLE, treble, isEnabled, equalizer, detail = "Everything above about 10 kHz.")
        }
    }
}

// Amplifier

@Composable
internal fun SoundAmplifierSection(
    boost: Float,
    preamp: Float,
    headroom: Float,
    isEnabled: Boolean,
    isLimiterOn: Boolean,
    isAutoHeadroomOn: Boolean,
    isNormalising: Boolean,
    equalizer: EqualizerStore,
) {
    SoundSection("Amplifier") {
        SoundCard {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Volume boost", style = BoomFont.itemTitle, color = BoomColor.textSecondary,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {}
                            .testTag("sound.boost.hero"),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            faderReadout(boost), fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold,
                            color = if (boost > 0 && isEnabled) BoomColor.accent else BoomColor.textPrimary, maxLines = 1,
                            style = BoomFont.time.copy(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "dB", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = BoomColor.textSecondary,
                            modifier = Modifier.padding(bottom = 5.dp),
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            SoundControl.percent(boost), fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                            color = BoomColor.textSecondary, maxLines = 1, modifier = Modifier.padding(bottom = 5.dp),
                        )
                    }
                    SoundSlider(SoundControl.BOOST, boost, isEnabled, equalizer, showsHeader = false)
                }
                LimiterMeter(equalizer.meter, isLimiterOn, isEnabled, Modifier.padding(vertical = 12.dp))
            }
            Text(
                "Louder than full volume. Clip protection holds every peak under −0.3 dBFS, so quiet recordings gain " +
                    "nearly the whole boost and ones already mastered loud gain less.",
                style = BoomFont.caption, color = BoomColor.textSecondary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            CardDivider()
            SoundSlider(
                SoundControl.PREAMP, preamp, isEnabled, equalizer,
                detail = "The level going into the equaliser. Lower it if a big boost sounds strained.",
            )
            CardDivider()
            SoundSwitch(
                "Clip protection", "A limiter that keeps peaks below full scale, so nothing distorts.", "sound.limiter",
                isLimiterOn,
            ) { equalizer.setLimiterEnabled(it) }
            CardDivider()
            SoundSwitch(
                "Auto headroom",
                "Lowers the level by the curve's highest boost, so the bands alone never push into the limiter.",
                "sound.autoHeadroom", isAutoHeadroomOn,
                badge = if (isAutoHeadroomOn && headroom > 0) "−%.1f dB".format(java.util.Locale.ROOT, headroom) else null,
            ) { equalizer.setAutoHeadroomEnabled(it) }
            CardDivider()
            SoundSwitch(
                "Normalise loudness", "Plays every song at a similar level. Works even with the equaliser off.",
                "sound.normalise", isNormalising,
            ) { equalizer.setLoudnessNormalizationEnabled(it) }
        }
        ClipWarning(equalizer.meter, isLimiterOn = isLimiterOn || !isEnabled)
    }
}

/**
 * A switch row with a line saying what it does: the whole row is the target, the switch drawn on the right.
 *
 * @param isAvailable False: shown greyed out and untouchable, with [detail] saying why.
 */
@Composable
internal fun SoundSwitch(
    title: String,
    detail: String,
    tag: String,
    isOn: Boolean,
    modifier: Modifier = Modifier,
    badge: String? = null,
    isAvailable: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val haptics = rememberSoundHaptics()
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(isOn, enabled = isAvailable, role = Role.Switch) {
                haptics.toggle(it)
                onChange(it)
            }
            .testTag(tag)
            .alpha(if (isAvailable) 1f else 0.55f)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = BoomFont.itemTitle, color = BoomColor.textPrimary)
                if (badge != null) {
                    Text(
                        badge, style = BoomFont.time.copy(fontWeight = FontWeight.Bold), color = BoomColor.accent,
                        modifier = Modifier
                            .background(BoomColor.accent.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            Text(detail, style = BoomFont.caption, color = BoomColor.textSecondary)
        }
        SoundSwitchKnob(isOn, isAvailable)
    }
}

/** The drawn switch: yellow track and white knob when on. */
@Composable
internal fun SoundSwitchKnob(isOn: Boolean, isEnabled: Boolean = true) {
    val knob by animateDpAsState(if (isOn) 22.dp else 2.dp, label = "knob")
    Box(
        Modifier
            .size(51.dp, 31.dp)
            .background(if (isOn) BoomColor.accent else Color.White.copy(alpha = 0.18f), CircleShape),
    ) {
        Box(
            Modifier
                .offset(x = knob, y = 2.dp)
                .size(27.dp)
                .shadow(2.dp, CircleShape)
                .background(if (isEnabled) Color.White else Color(0xFFBFBFBF), CircleShape),
        )
    }
}

// Enhance

@Composable
internal fun SoundEnhanceSection(deepBass: Float, air: Float, isEnabled: Boolean, equalizer: EqualizerStore) {
    SoundSection("Enhance") {
        SoundCard {
            SoundSlider(
                SoundControl.DEEP_BASS, deepBass, isEnabled, equalizer,
                detail = "Adds harmonics of the lowest notes, so small speakers and earphones carry them.",
            )
            CardDivider()
            SoundSlider(SoundControl.AIR, air, isEnabled, equalizer, detail = "Adds a little harmonic sparkle to the top octave.")
        }
    }
}

// Stereo

@Composable
internal fun SoundStereoSection(
    width: Float,
    balance: Float,
    crossfeed: Float,
    isSpatial: Boolean,
    isHeadTracking: Boolean,
    isHeadTrackingAvailable: Boolean,
    isEnabled: Boolean,
    equalizer: EqualizerStore,
) {
    SoundSection("Stereo") {
        SoundCard {
            SoundSlider(SoundControl.WIDTH, width, isEnabled, equalizer, detail = "Narrower toward mono, or wider than the recording.")
            CardDivider()
            SoundSlider(SoundControl.BALANCE, balance, isEnabled, equalizer)
            CardDivider()
            SoundSwitch(
                "Spatial",
                "For headphones: the two sides sound like a pair of loudspeakers in front of you, each reaching both " +
                    "ears as in a room. On the phone's own speaker it only changes the tone.",
                "sound.spatial", isSpatial,
            ) { isOn ->
                // Like the sliders: turning it on means wanting to hear it.
                if (isOn && !equalizer.settings.isEnabled) equalizer.setEnabled(true)
                equalizer.setSpatialEnabled(isOn)
            }
            AnimatedVisibility(isSpatial, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .padding(vertical = 12.dp)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(BoomColor.accent.copy(alpha = 0.5f)),
                    )
                    SoundSwitch(
                        "Follow head movement",
                        if (isHeadTrackingAvailable) {
                            "The loudspeakers stay where they are while you turn your head, and come round to face you " +
                                "again after a few seconds."
                        } else {
                            "Needs headphones that report head movement to this phone."
                        },
                        "sound.headTracking", isHeadTracking && isHeadTrackingAvailable,
                        modifier = Modifier.padding(start = 16.dp),
                        isAvailable = isHeadTrackingAvailable,
                    ) { equalizer.setHeadTrackingEnabled(it) }
                }
            }
            CardDivider()
            SoundSlider(
                SoundControl.CROSSFEED, crossfeed, isEnabled && !isSpatial, equalizer,
                modifier = Modifier.alpha(if (isSpatial) 0.5f else 1f),
                detail = if (isSpatial) {
                    "Not needed while Spatial is on: Spatial already lets each side reach both ears."
                } else {
                    "For headphones: a little of each side reaches the other ear, later and softer, as in front of loudspeakers."
                },
            )
        }
    }
}

// Output

@Composable
internal fun SoundOutputSection(meter: io.github.boomboom.logic.LevelMeter) {
    SoundSection("Output", trailing = { Text("RMS · peak · dBFS", style = BoomFont.caption, color = BoomColor.textTertiary) }) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(BoomColor.surface, RoundedCornerShape(BoomMetrics.cardRadius + 6.dp))
                .padding(14.dp),
        ) { SoundOutputMeters(meter) }
    }
}
