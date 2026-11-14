package io.github.boomboom.ui.browse.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.boomboom.logic.CacheStore
import io.github.boomboom.logic.EqualizerPreset
import io.github.boomboom.ui.browse.BrowseRoute
import io.github.boomboom.ui.browse.LocalShellRouter
import io.github.boomboom.ui.browse.PageFrame
import io.github.boomboom.ui.browse.PageTitle
import io.github.boomboom.ui.browse.rememberPageModel
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.ConfirmDialog
import io.github.boomboom.ui.design.DownloadSize
import io.github.boomboom.ui.design.LocalAppScope
import io.github.boomboom.ui.design.LocalAppServices
import io.github.boomboom.ui.design.LocalBoomNavigator
import io.github.boomboom.ui.design.songsText
import io.github.boomboom.ui.sound.SoundSwitch
import kotlinx.coroutines.launch

/**
 * Settings, reached from the account menu: the accounts, playback and sound, downloads, the songs the app keeps by
 * itself, and the app (its version, and a way to report a problem).
 */
@Composable
fun SettingsScreen() {
    val services = LocalAppServices.current
    val scroll = rememberPageModel("scroll") { ScrollState(0) }
    val titleHeight = with(LocalDensity.current) { 40.dp.toPx() }
    LaunchedEffect(Unit) { services.cache.refresh() }

    PageFrame("Settings", isTitleShown = scroll.value > titleHeight) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .testTag("settings.list")
                .padding(bottom = 32.dp),
        ) {
            PageTitle("Settings", Modifier.padding(top = 4.dp, bottom = 16.dp))
            Column(
                Modifier.padding(horizontal = BoomMetrics.margin),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                AccountsSection()
                PlaybackSection()
                DownloadsSection()
                CacheSection()
                AboutSection()
            }
        }
    }
}

@Composable
private fun PlaybackSection() {
    val services = LocalAppServices.current
    val navigator = LocalBoomNavigator.current
    val player by services.player.state.collectAsState()
    val equalizer by services.equalizer.state.collectAsState()
    val isOn = equalizer.settings.isEnabled
    SettingsSection("Playback", tag = "settings.playback") {
        SoundSwitch(
            "Autoplay similar songs", "When the queue ends, keep playing songs like the last one.", "settings.autoplay",
            player.isAutoplayEnabled,
        ) { services.player.isAutoplayEnabled = it }
        RowDivider()
        SettingsRow(
            "Equaliser and amplifier", Modifier.testTag("settings.equalizer"), value = if (isOn) "On" else "Off",
            valueColor = if (isOn) BoomColor.accent else BoomColor.textSecondary, showsChevron = true,
        ) { navigator.openEqualizer() }
        RowDivider()
        PresetMenu()
        RowDivider()
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .semantics(mergeDescendants = true) {}
                .testTag("settings.audio"),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("Audio quality", style = BoomFont.itemTitle, color = BoomColor.textPrimary)
            Text("The best stream on offer for your account is always used.", style = BoomFont.itemSubtitle, color = BoomColor.textSecondary)
            player.audioFormat?.let { Text(it, style = BoomFont.itemSubtitle, color = BoomColor.accent, modifier = Modifier.padding(top = 2.dp)) }
            // Where it is going, and whether anything had to be converted on the way.
            player.audioOutput?.let { Text("To $it", style = BoomFont.itemSubtitle, color = BoomColor.textSecondary) }
        }
    }
}

/**
 * The sound presets without opening the Sound screen: Off, the starred ones first, then the listener's own, then the
 * rest; choosing one applies it (and turns the equaliser on). The last entry opens the Sound screen.
 */
@Composable
private fun PresetMenu() {
    val services = LocalAppServices.current
    val navigator = LocalBoomNavigator.current
    val store = services.equalizer
    val equalizer by store.state.collectAsState()
    var isOpen by remember { mutableStateOf(false) }
    val isOn = equalizer.settings.isEnabled
    val selected = equalizer.presets.firstOrNull { it.id == equalizer.selectedPresetId }
    val favourites = equalizer.favoritePresets
    val starred = favourites.map { it.id }.toSet()
    val own = equalizer.presets.filter { !it.isBuiltIn && it.id !in starred }
    val builtIn = equalizer.presets.filter { it.isBuiltIn && it.id !in starred }
    val shown = if (isOn) selected?.name ?: "Custom" else "Off"

    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(role = Role.DropdownList) { isOpen = true }
                .semantics(mergeDescendants = true) {
                    contentDescription = "Sound preset"
                    stateDescription = shown
                }
                .testTag("settings.preset")
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Sound preset", style = BoomFont.itemTitle, color = BoomColor.textPrimary, modifier = Modifier.weight(1f))
            Text(
                shown, style = BoomFont.itemSubtitle, color = if (isOn) BoomColor.accent else BoomColor.textSecondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("settings.preset.value"),
            )
            Icon(Icons.Filled.UnfoldMore, null, tint = BoomColor.textTertiary, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(
            isOpen, { isOpen = false }, containerColor = BoomColor.surfaceRaised,
            modifier = Modifier.testTag("settings.preset.menu"),
        ) {
            val choose: (EqualizerPreset) -> Unit = { preset ->
                isOpen = false
                store.select(preset)
            }
            DropdownMenuItem(
                text = { Text("Off") }, leadingIcon = { Mark(!isOn) },
                onClick = {
                    isOpen = false
                    store.setEnabled(false)
                },
                modifier = Modifier.testTag("settings.preset.off"),
            )
            if (favourites.isNotEmpty()) {
                MenuHeading("Favourites")
                for (preset in favourites) PresetChoice(preset, isOn && preset.id == selected?.id, isStarred = true, choose)
            }
            if (own.isNotEmpty()) {
                MenuHeading("Your presets")
                for (preset in own) PresetChoice(preset, isOn && preset.id == selected?.id, isStarred = false, choose)
            }
            MenuHeading("Presets")
            for (preset in builtIn) PresetChoice(preset, isOn && preset.id == selected?.id, isStarred = false, choose)
            HorizontalDivider(color = BoomColor.separator, modifier = Modifier.padding(vertical = 4.dp))
            DropdownMenuItem(
                text = { Text("Open the equaliser") }, leadingIcon = { Icon(Icons.Outlined.Tune, null) },
                onClick = {
                    isOpen = false
                    navigator.openEqualizer()
                },
                modifier = Modifier.testTag("settings.preset.open"),
            )
        }
    }
}

@Composable
private fun PresetChoice(preset: EqualizerPreset, isSelected: Boolean, isStarred: Boolean, choose: (EqualizerPreset) -> Unit) {
    DropdownMenuItem(
        text = { Text(preset.name, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal) },
        leadingIcon = {
            when {
                isSelected -> Icon(Icons.Filled.Check, null, tint = BoomColor.accent)
                isStarred -> Icon(Icons.Filled.Star, null, tint = BoomColor.accent)
                else -> Spacer(Modifier.width(24.dp))
            }
        },
        onClick = { choose(preset) },
        modifier = Modifier
            .semantics { if (isSelected) stateDescription = "Selected" }
            .testTag("settings.preset.option"),
    )
}

@Composable
private fun Mark(isOn: Boolean) {
    if (isOn) Icon(Icons.Filled.Check, null, tint = BoomColor.accent) else Spacer(Modifier.width(24.dp))
}

/** A small heading between the groups of a menu. Not a choice: it takes no taps. */
@Composable
private fun MenuHeading(text: String) {
    Text(
        text, style = BoomFont.caption.copy(fontWeight = FontWeight.SemiBold), color = BoomColor.textTertiary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun DownloadsSection() {
    val services = LocalAppServices.current
    val router = LocalShellRouter.current
    val downloads by services.downloads.state.collectAsState()
    SettingsSection(
        "Downloads", tag = "settings.downloadsSection",
        footer = "Downloads are yours: they stay until you remove them. The cache below is kept by the app and trims itself.",
    ) {
        SettingsRow(
            "Downloads", Modifier.testTag("settings.downloads"),
            value = "${songsText(downloads.downloaded.size)} · ${DownloadSize.text(downloads.totalBytes)}", showsChevron = true,
        ) { router.push(BrowseRoute.Downloads) }
        RowDivider()
        SoundSwitch(
            "Download over mobile data", "Off: downloads wait for Wi-Fi.", "settings.mobileData", downloads.isMobileDataAllowed,
        ) { services.downloads.isMobileDataAllowed = it }
    }
}

@Composable
private fun CacheSection() {
    val services = LocalAppServices.current
    val appScope = LocalAppScope.current
    val cache by services.cache.state.collectAsState()
    var isAsking by remember { mutableStateOf(false) }
    var isPickingLimit by remember { mutableStateOf(false) }
    val choices = CacheStore.LIMIT_CHOICES.let { if (cache.limitBytes in it) it else (it + cache.limitBytes).sorted() }
    SettingsSection(
        "Automatic song cache", tag = "settings.cache",
        footer = "BoomBoom keeps every song it fetches by itself: the ones you played and the next few in your queue. " +
            "They then start at once, use no data the next time and play without a connection. You did not have to " +
            "save them, and they are not downloads in your library. When the limit is reached, the ones played longest " +
            "ago are removed.",
    ) {
        SettingsRow("Songs in the cache", Modifier.testTag("settings.cache.count"), value = "${cache.songCount}")
        RowDivider()
        SettingsRow("Space used", Modifier.testTag("settings.cache.used"), value = DownloadSize.text(cache.usedBytes))
        RowDivider()
        Box {
            SettingsRow(
                "Use up to", Modifier.testTag("settings.cache.limit"), value = DownloadSize.text(cache.limitBytes),
                role = Role.DropdownList,
            ) { isPickingLimit = true }
            DropdownMenu(isPickingLimit, { isPickingLimit = false }, containerColor = BoomColor.surfaceRaised) {
                for (choice in choices) {
                    DropdownMenuItem(
                        text = { Text(DownloadSize.text(choice)) }, leadingIcon = { Mark(choice == cache.limitBytes) },
                        onClick = {
                            isPickingLimit = false
                            services.cache.limitBytes = choice
                        },
                    )
                }
            }
        }
        RowDivider()
        SettingsRow(
            "Empty the cache", Modifier.testTag("settings.cache.clear"), titleColor = BoomColor.destructive,
            enabled = cache.songCount > 0 || cache.usedBytes > 0,
        ) { isAsking = true }
    }
    if (isAsking) {
        ConfirmDialog(
            "Empty the song cache?", "Songs start a little slower and need a connection until they are played again.",
            confirm = "Empty the cache", tag = "settings.clearCacheQuestion",
            onConfirm = { appScope.launch { services.cache.clear() } },
            onDismiss = { isAsking = false },
        )
    }
}

@Composable
private fun AboutSection() {
    val navigator = LocalBoomNavigator.current
    val context = LocalContext.current
    val version = remember(context) { BuildInfo.version(context) }
    SettingsSection("About", tag = "settings.about") {
        SettingsRow("Version", Modifier.testTag("settings.version"), value = version)
        RowDivider()
        SettingsRow(
            "Report a problem", Modifier.testTag("settings.reportProblem"),
            detail = "Makes a file of what the app did, with nothing personal in it, for you to send to whoever gave you BoomBoom.",
            icon = Icons.Outlined.BugReport,
        ) { navigator.reportProblem() }
    }
}
