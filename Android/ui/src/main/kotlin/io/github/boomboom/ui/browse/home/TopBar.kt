package io.github.boomboom.ui.browse.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import io.github.boomboom.ui.R
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.boomboom.ui.browse.BrowseRoute
import io.github.boomboom.ui.browse.LocalShellRouter
import io.github.boomboom.ui.design.ArtworkView
import io.github.boomboom.ui.design.BarIconButton
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.LocalAppScope
import io.github.boomboom.ui.design.LocalAppServices
import io.github.boomboom.ui.design.LocalBoomNavigator
import kotlinx.coroutines.launch

/**
 * The bar at the top of a tab's root: the brand (or a title), search, and the account button.
 *
 * @param actions Buttons before search (Library's History).
 */
@Composable
fun TopBar(title: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val router = LocalShellRouter.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(BoomColor.background)
            .statusBarsPadding()
            .height(56.dp)
            .padding(start = BoomMetrics.margin, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (title != null) {
            Text(title, style = BoomFont.pageTitle, color = BoomColor.textPrimary, modifier = Modifier.semantics { heading() })
        } else {
            BrandMark()
        }
        Spacer(Modifier.weight(1f))
        actions()
        BarIconButton(
            Icons.Outlined.Search, "Search", { router.push(BrowseRoute.Search(null)) },
            Modifier.testTag("topbar.search"),
        )
        AccountButton()
    }
}

/**
 * A menu for everyone: the accounts (another one's tap switches to it), signing in, adding an account, and Settings.
 * Settings must be within reach signed out too: the sound, the cache and the downloads need no account.
 */
@Composable
private fun AccountButton() {
    val services = LocalAppServices.current
    val navigator = LocalBoomNavigator.current
    val router = LocalShellRouter.current
    val appScope = LocalAppScope.current
    val session by services.session.state.collectAsState()
    var isOpen by remember { mutableStateOf(false) }
    val hasAccounts = session.isSignedIn || session.accounts.isNotEmpty()
    Box {
        val account = session.account
        Box(
            Modifier
                .size(BoomMetrics.minimumTarget)
                .clip(CircleShape)
                .clickable(role = Role.Button) { isOpen = true }
                .semantics { contentDescription = "Account and settings" }
                .testTag("topbar.account"),
            contentAlignment = Alignment.Center,
        ) {
            if (account != null) {
                ArtworkView(account.artwork, width = 30.dp, isCircle = true)
            } else {
                // Filled once an account is known, an outline while nobody is signed in.
                Icon(
                    if (hasAccounts) Icons.Filled.AccountCircle else Icons.Outlined.AccountCircle, null,
                    tint = BoomColor.textPrimary, modifier = Modifier.size(28.dp),
                )
            }
        }
        DropdownMenu(
            expanded = isOpen, onDismissRequest = { isOpen = false },
            containerColor = BoomColor.surfaceRaised, modifier = Modifier.testTag("account.menu"),
        ) {
            for (entry in session.accounts) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(entry.account?.name ?: "Signed-in account", style = BoomFont.itemTitle, color = BoomColor.textPrimary)
                            entry.account?.handle?.let { Text(it, style = BoomFont.caption, color = BoomColor.textSecondary) }
                        }
                    },
                    leadingIcon = {
                        if (entry.isActive) Icon(Icons.Filled.Check, null, tint = BoomColor.accent) else Spacer(Modifier.width(24.dp))
                    },
                    onClick = {
                        isOpen = false
                        if (!entry.isActive) appScope.launch { services.session.switchAccount(entry.id) }
                    },
                    modifier = Modifier.testTag("account.entry"),
                )
            }
            if (session.accounts.isNotEmpty()) HorizontalDivider(color = BoomColor.separator)
            // The active account's sign-in ended while other accounts are still signed in.
            if (!session.isSignedIn) {
                DropdownMenuItem(
                    text = { Text("Sign in") }, leadingIcon = { Icon(Icons.Outlined.AccountCircle, null) },
                    onClick = { isOpen = false; navigator.signIn() }, modifier = Modifier.testTag("account.signIn"),
                )
            }
            if (session.canAddAccount && hasAccounts) {
                DropdownMenuItem(
                    text = { Text("Add another account") }, leadingIcon = { Icon(Icons.Outlined.PersonAdd, null) },
                    onClick = { isOpen = false; navigator.addAccount() }, modifier = Modifier.testTag("account.add"),
                )
            }
            DropdownMenuItem(
                text = { Text("Settings") }, leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                onClick = { isOpen = false; router.push(BrowseRoute.Settings) }, modifier = Modifier.testTag("account.settings"),
            )
        }
    }
}

/** BoomBoom's name with its mark: a yellow disc, a black play mark between two black brackets (`res/drawable/boom_mark`). */
@Composable
fun BrandMark() {
    val fontScale = LocalDensity.current.fontScale
    Row(
        Modifier.clearAndSetSemantics {
            contentDescription = "BoomBoom"
            heading()
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Image(painterResource(R.drawable.boom_mark), contentDescription = null, modifier = Modifier.size(26.dp))
        // The name grows with the font size only so far, so the bar keeps room for its buttons.
        Text(
            "BoomBoom", color = BoomColor.textPrimary, fontWeight = FontWeight.Black,
            fontSize = (21 * minOf(fontScale, 1.3f) / fontScale).sp, letterSpacing = (-0.6).sp,
        )
    }
}
