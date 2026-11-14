package io.github.boomboom.ui.design

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * The question a screen asks before something that cannot be undone (removing downloads, signing out, emptying the
 * cache): a title, what will happen, the action in red, and Cancel. Text only: a dialog that holds a text field is
 * drawn inside the screen instead (see `NewPlaylistPrompt`).
 *
 * The buttons carry the tags "[tag].confirm" and "[tag].cancel".
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    tag: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    isDestructive: Boolean = true,
    dismiss: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BoomColor.surfaceRaised,
        modifier = Modifier.testTag(tag),
        title = { Text(title, style = BoomFont.sectionTitle, color = BoomColor.textPrimary) },
        text = { Text(message, style = BoomFont.itemSubtitle, color = BoomColor.textSecondary) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm()
                },
                modifier = Modifier.testTag("$tag.confirm"),
            ) {
                Text(confirm, style = BoomFont.button, color = if (isDestructive) BoomColor.destructive else BoomColor.accent)
            }
        },
        dismissButton = {
            TextButton(onDismiss, Modifier.testTag("$tag.cancel")) {
                Text(dismiss, style = BoomFont.button, color = BoomColor.textPrimary)
            }
        },
    )
}
