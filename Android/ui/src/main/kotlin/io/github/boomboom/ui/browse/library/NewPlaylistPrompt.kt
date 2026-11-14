package io.github.boomboom.ui.browse.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.boomboom.models.PlaylistPrivacy
import io.github.boomboom.ui.browse.collections.explanation
import io.github.boomboom.ui.browse.collections.title
import io.github.boomboom.ui.browse.userMessage
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.LocalAppScope
import io.github.boomboom.ui.design.LocalAppServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Where the new playlist's request stands. One at a time. */
private sealed class CreateStatus {
    data object Idle : CreateStatus()
    data object Creating : CreateStatus()
    data class Failed(val message: String) : CreateStatus()
}

/**
 * The form for a new playlist (a name, a description, who can see it), drawn over the Library inside its own window
 * as a dialog looks: a Material dialog holding a text field never settles under the screens' tests, and in the app a
 * card in the window stays above the keyboard with the rest of the content.
 *
 * The keyboard goes as soon as the name is submitted (Create, or the keyboard's own key), so it never covers the
 * playlist that opens next. Back or a tap outside cancels, except while the playlist is being made.
 *
 * @param onCreated Called with the new playlist's id once it exists; the prompt has closed itself.
 */
@Composable
fun NewPlaylistPrompt(onClose: () -> Unit, onCreated: (String) -> Unit) {
    val services = LocalAppServices.current
    val appScope = LocalAppScope.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val nameFocus = remember { FocusRequester() }
    var name by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var privacy by remember { mutableStateOf(PlaylistPrivacy.PRIVATE) }
    var status by remember { mutableStateOf<CreateStatus>(CreateStatus.Idle) }
    var isPickingPrivacy by remember { mutableStateOf(false) }
    val trimmed = name.trim()
    val isCreating = status == CreateStatus.Creating

    fun cancel() {
        if (isCreating) return
        keyboard?.hide()
        focusManager.clearFocus()
        onClose()
    }

    fun create() {
        if (trimmed.isEmpty() || isCreating) return
        keyboard?.hide()
        focusManager.clearFocus()
        status = CreateStatus.Creating
        val description = details.trim()
        val chosen = privacy
        appScope.launch {
            try {
                val id = services.api.createPlaylist(trimmed, description, chosen, emptyList())
                onClose()
                onCreated(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                status = CreateStatus.Failed("Couldn't create the playlist. " + userMessage(failure))
            }
        }
    }

    LaunchedEffect(Unit) { nameFocus.requestFocus() }
    BackHandler { cancel() }

    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = BoomColor.accent, cursorColor = BoomColor.accent, focusedLabelColor = BoomColor.accent,
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(remember { MutableInteractionSource() }, indication = null) { cancel() }
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
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)
                .testTag("newPlaylist"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "New playlist", style = BoomFont.messageTitle, color = BoomColor.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
            OutlinedTextField(
                name, { name = it }, label = { Text("Name") }, singleLine = true, enabled = !isCreating, colors = colors,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { create() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(nameFocus)
                    .testTag("newPlaylist.name"),
            )
            OutlinedTextField(
                details, { details = it }, label = { Text("Description (optional)") }, maxLines = 4, enabled = !isCreating,
                colors = colors, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("newPlaylist.description"),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(enabled = !isCreating, role = Role.DropdownList) { isPickingPrivacy = true }
                            .testTag("newPlaylist.privacy"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Privacy", style = BoomFont.itemTitle, color = BoomColor.textPrimary, modifier = Modifier.weight(1f))
                        Text(privacy.title, style = BoomFont.itemTitle, color = BoomColor.textSecondary)
                        Icon(Icons.Filled.ArrowDropDown, null, tint = BoomColor.textSecondary)
                    }
                    DropdownMenu(isPickingPrivacy, { isPickingPrivacy = false }, containerColor = BoomColor.surfaceRaised) {
                        for (choice in listOf(PlaylistPrivacy.PRIVATE, PlaylistPrivacy.UNLISTED, PlaylistPrivacy.PUBLIC)) {
                            DropdownMenuItem({ Text(choice.title) }, {
                                privacy = choice
                                isPickingPrivacy = false
                            })
                        }
                    }
                }
                Text(privacy.explanation, style = BoomFont.caption, color = BoomColor.textSecondary)
            }
            (status as? CreateStatus.Failed)?.let { failed ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.WarningAmber, null, tint = BoomColor.destructive, modifier = Modifier.size(16.dp))
                    Text(failed.message, style = BoomFont.caption, color = BoomColor.destructive, modifier = Modifier.testTag("newPlaylist.error"))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(::cancel, enabled = !isCreating, modifier = Modifier.testTag("newPlaylist.cancel")) {
                    Text("Cancel", color = BoomColor.textPrimary, style = BoomFont.button)
                }
                Box(Modifier.heightIn(min = 48.dp).widthIn(min = 80.dp), contentAlignment = Alignment.Center) {
                    if (isCreating) {
                        CircularProgressIndicator(color = BoomColor.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    } else {
                        TextButton(::create, enabled = trimmed.isNotEmpty(), modifier = Modifier.testTag("newPlaylist.create")) {
                            Text(
                                "Create", style = BoomFont.button.copy(fontWeight = FontWeight.Bold),
                                color = if (trimmed.isNotEmpty()) BoomColor.accent else BoomColor.textTertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}
