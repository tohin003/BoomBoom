package io.github.boomboom.ui.browse

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import io.github.boomboom.models.BrowseEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job

/** The app's three tabs. */
enum class BrowseTab(val title: String, val tag: String) {
    HOME("Home", "tab.home"),
    EXPLORE("Explore", "tab.explore"),
    LIBRARY("Library", "tab.library"),
}

/** Where the frame opens: a tab, or straight onto a screen (for deep links, diagnostics and tests). */
sealed class BrowseStart {
    data class Tab(val tab: BrowseTab) : BrowseStart()

    /** Search, optionally with words in the field: submitted (results show) or not (suggestions show). */
    data class Search(val query: String?, val submitted: Boolean = true) : BrowseStart()

    /** An album, an artist, a playlist or a page of shelves, pushed onto Home. */
    data class Page(val endpoint: BrowseEndpoint) : BrowseStart()

    data object Charts : BrowseStart()
    data object Moods : BrowseStart()
    data object Settings : BrowseStart()

    /** The Downloads screen, pushed onto Library. */
    data object Downloads : BrowseStart()
}

/** A screen in a tab's stack. */
sealed class BrowseRoute {
    /** The tab's own first screen. */
    data class Root(val tab: BrowseTab) : BrowseRoute()

    /** An album, an artist, a playlist, or any other page of shelves. */
    data class Page(val endpoint: BrowseEndpoint) : BrowseRoute()

    data class Search(val query: String? = null, val submitted: Boolean = true) : BrowseRoute()
    data object Charts : BrowseRoute()
    data object Moods : BrowseRoute()
    data object Settings : BrowseRoute()

    /** The songs, albums and playlists kept on the phone. */
    data object Downloads : BrowseRoute()

    /** One downloaded album or playlist, by its browse id. */
    data class DownloadedCollection(val id: String) : BrowseRoute()
}

/**
 * One screen in a stack, and what it keeps while it is in the stack: its page model, its scroll position, and a scope
 * for its requests. A page covered by another keeps all of it; a page popped cancels its scope, so whatever it still
 * had out is dropped.
 */
class BackStackEntry internal constructor(val route: BrowseRoute, parent: CoroutineScope, val id: Long) {
    val scope: CoroutineScope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext.job))
    private val kept = HashMap<String, Any>()

    /** The value kept under [key] for as long as the entry lives, made on first use. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> keep(key: String, create: () -> T): T = kept.getOrPut(key, create) as T

    internal fun dispose() = scope.cancel()
}

/**
 * The tabs' navigation state: one stack per tab, each beginning with the tab's own screen. One per frame; lives on the
 * main thread.
 *
 * @param scope The frame's scope; each entry's scope is a child of it.
 */
class ShellRouter(start: BrowseStart, private val scope: CoroutineScope) {
    private var nextId = 0L
    private val stacks = BrowseTab.entries.associateWith { tab -> mutableStateListOf(entry(BrowseRoute.Root(tab))) }

    var selected by mutableStateOf(BrowseTab.HOME)
        private set

    /** Goes up when the listener taps the tab that is already selected and at its root: scroll to the top. */
    private val reselectTicks = mutableStateMapOf<BrowseTab, Int>()

    init {
        val (tab, route) = when (start) {
            is BrowseStart.Tab -> start.tab to null
            is BrowseStart.Search -> BrowseTab.HOME to BrowseRoute.Search(start.query, start.submitted)
            is BrowseStart.Page -> BrowseTab.HOME to BrowseRoute.Page(start.endpoint)
            BrowseStart.Charts -> BrowseTab.EXPLORE to BrowseRoute.Charts
            BrowseStart.Moods -> BrowseTab.EXPLORE to BrowseRoute.Moods
            BrowseStart.Settings -> BrowseTab.HOME to BrowseRoute.Settings
            BrowseStart.Downloads -> BrowseTab.LIBRARY to BrowseRoute.Downloads
        }
        selected = tab
        route?.let { stacks.getValue(tab).add(entry(it)) }
    }

    private fun entry(route: BrowseRoute) = BackStackEntry(route, scope, nextId++)

    fun stack(tab: BrowseTab): List<BackStackEntry> = stacks.getValue(tab)

    /** The screen shown now. */
    val current: BackStackEntry get() = stacks.getValue(selected).last()

    /** The routes pushed onto a tab, without its root (for tests and diagnostics). */
    fun path(tab: BrowseTab): List<BrowseRoute> = stacks.getValue(tab).drop(1).map { it.route }

    fun reselectTick(tab: BrowseTab): Int = reselectTicks[tab] ?: 0

    /** A tab bar tap: switch tabs; on the selected tab, back to its root, then to the top. */
    fun select(tab: BrowseTab) {
        if (tab != selected) {
            selected = tab
            return
        }
        val stack = stacks.getValue(tab)
        if (stack.size > 1) {
            popTo(stack, 1)
        } else {
            reselectTicks[tab] = reselectTick(tab) + 1
        }
    }

    /** Pushes onto the current tab, ignoring a second tap that would push the same page twice. */
    fun push(route: BrowseRoute) {
        val stack = stacks.getValue(selected)
        if (stack.last().route == route) return
        stack.add(entry(route))
    }

    /**
     * The back gesture: the page before in this tab; at a tab's root, Home. Returns false at Home's root, where back
     * leaves the app.
     */
    fun back(): Boolean {
        val stack = stacks.getValue(selected)
        if (stack.size > 1) {
            popTo(stack, stack.size - 1)
            return true
        }
        if (selected != BrowseTab.HOME) {
            selected = BrowseTab.HOME
            return true
        }
        return false
    }

    /** Whether [back] has somewhere to go. */
    val canGoBack: Boolean get() = stacks.getValue(selected).size > 1 || selected != BrowseTab.HOME

    private fun popTo(stack: SnapshotStateList<BackStackEntry>, size: Int) {
        while (stack.size > size) stack.removeAt(stack.lastIndex).dispose()
    }
}

/** The router of the frame a screen is in. */
val LocalShellRouter = staticCompositionLocalOf<ShellRouter> { error("BrowseShell provides the router") }

/** The stack entry a screen is drawn for. */
val LocalBackStackEntry = staticCompositionLocalOf<BackStackEntry> { error("BrowseShell provides the entry") }

/** A page model kept in the screen's stack entry, made with the entry's scope on first use. */
@Composable
fun <T : Any> rememberPageModel(key: String = "model", create: (CoroutineScope) -> T): T {
    val entry = LocalBackStackEntry.current
    return entry.keep(key) { create(entry.scope) }
}

/** A list's scroll position kept in the screen's stack entry, so it is where the listener left it on the way back. */
@Composable
fun rememberPageListState(key: String = "list"): LazyListState {
    val entry = LocalBackStackEntry.current
    return entry.keep(key) { LazyListState() }
}
