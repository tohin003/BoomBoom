package io.github.boomboom.ui.browse.collections

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.boomboom.models.AlbumPage
import io.github.boomboom.models.ArtistPage
import io.github.boomboom.models.LikeStatus
import io.github.boomboom.models.MusicAPI
import io.github.boomboom.models.PlaylistPage
import io.github.boomboom.ui.browse.LoadState
import io.github.boomboom.ui.browse.MoreState
import io.github.boomboom.ui.browse.OptimisticToggle
import io.github.boomboom.ui.browse.Paged
import io.github.boomboom.ui.browse.RequestSlot
import io.github.boomboom.ui.browse.userMessage
import io.github.boomboom.ui.design.quietly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// The album, artist and playlist pages' state. Each page loads once, again on Retry or pull to refresh (which keeps the
// page on screen until the new one arrives, and keeps it if the refresh fails), and cancels what is out when it is
// popped. Save and Subscribe are OptimisticToggles: shown at once, sent one request at a time, and sent in the app's
// scope (the listener asked for them, so they finish even if the page is closed meanwhile). Main thread only.

/**
 * Sends a toggle's wishes one at a time through [send], which returns whether the service took it.
 */
private class ToggleSender(
    private val appScope: CoroutineScope,
    private val read: () -> OptimisticToggle,
    private val write: (OptimisticToggle) -> Unit,
    private val send: suspend (Boolean) -> Boolean,
) {
    fun tap() {
        val (next, value) = read().flip()
        write(next)
        if (value != null) dispatch(value)
    }

    private fun dispatch(value: Boolean) {
        appScope.launch {
            val succeeded = send(value)
            val (next, again) = read().answered(succeeded)
            write(next)
            if (again != null) dispatch(again)
        }
    }
}

class AlbumModel(val id: String, private val api: MusicAPI, scope: CoroutineScope, appScope: CoroutineScope) {
    var state by mutableStateOf<LoadState<AlbumPage>>(LoadState.Loading)
        private set

    /** In the listener's library. */
    var library by mutableStateOf(OptimisticToggle(false))
        private set

    private val request = RequestSlot(scope)
    private val sender = ToggleSender(appScope, { library }, { library = it }) { adding ->
        val token = token(adding)
        token != null && quietly { api.sendLibraryFeedback(token) } != null
    }

    fun loadIfNeeded() {
        if (state.isLoaded || request.isBusy) return
        load()
    }

    fun retry() {
        load()
    }

    fun refresh(): Job = load()

    private fun load(): Job {
        if (!state.isLoaded) state = LoadState.Loading
        return request.start({ api.album(id) }) { result ->
            result.onSuccess { page ->
                state = LoadState.Loaded(page)
                library = library.reload(page.libraryTokens?.isInLibrary ?: false)
            }.onFailure { if (!state.isLoaded) state = LoadState.Failed(userMessage(it)) }
        }
    }

    /** Save to library, or remove from it. Albums the service gives no token for that change cannot be toggled. */
    fun toggleLibrary() {
        if (token(!library.isOn) == null) return
        sender.tap()
    }

    private fun token(adding: Boolean): String? {
        val tokens = state.valueOrNull?.libraryTokens
        return if (adding) tokens?.add else tokens?.remove
    }
}

class ArtistModel(val id: String, private val api: MusicAPI, scope: CoroutineScope, appScope: CoroutineScope) {
    var state by mutableStateOf<LoadState<ArtistPage>>(LoadState.Loading)
        private set
    var subscription by mutableStateOf(OptimisticToggle(false))
        private set

    private val request = RequestSlot(scope)
    private val sender = ToggleSender(appScope, { subscription }, { subscription = it }) { subscribing ->
        val channel = state.valueOrNull?.artist?.id
        channel != null && quietly { api.subscribe(channel, subscribing) } != null
    }

    fun loadIfNeeded() {
        if (state.isLoaded || request.isBusy) return
        load()
    }

    fun retry() {
        load()
    }

    fun refresh(): Job = load()

    private fun load(): Job {
        if (!state.isLoaded) state = LoadState.Loading
        return request.start({ api.artist(id) }) { result ->
            result.onSuccess { page ->
                state = LoadState.Loaded(page)
                subscription = subscription.reload(page.isSubscribed)
            }.onFailure { if (!state.isLoaded) state = LoadState.Failed(userMessage(it)) }
        }
    }

    fun toggleSubscription() {
        if (state.valueOrNull == null) return
        sender.tap()
    }
}

class PlaylistModel(val id: String, private val api: MusicAPI, scope: CoroutineScope, appScope: CoroutineScope) {
    /** The page, its songs so far (`content.tracks`) and the next part of them. */
    var state by mutableStateOf<LoadState<Paged<PlaylistPage>>>(LoadState.Loading)
        private set

    /** Saved to the library (liked). */
    var saved by mutableStateOf(OptimisticToggle(false))
        private set

    private val request = RequestSlot(scope)

    /** The next songs; dropped whenever the page is replaced. */
    private val more = RequestSlot(scope)
    private val sender = ToggleSender(appScope, { saved }, { saved = it }) { saving ->
        val playlist = state.valueOrNull?.content?.playlist?.id
        playlist != null && quietly { api.ratePlaylist(playlist, if (saving) LikeStatus.LIKE else LikeStatus.INDIFFERENT) } != null
    }

    fun loadIfNeeded() {
        if (state.isLoaded || request.isBusy) return
        load()
    }

    fun retry() {
        load()
    }

    /** Pull to refresh. Songs keep loading as the listener scrolls until the new page arrives. */
    fun refresh(): Job = load()

    private fun load(): Job {
        if (!state.isLoaded) show(LoadState.Loading)
        return request.start({ api.playlist(id) }) { result ->
            result.onSuccess { page ->
                show(LoadState.Loaded(Paged(page, MoreState.of(page.continuation))))
                saved = saved.reload(page.likeStatus == LikeStatus.LIKE)
            }.onFailure { if (!state.isLoaded) show(LoadState.Failed(userMessage(it))) }
        }
    }

    /** The only place the page is replaced. A load-more for the old songs goes with them. */
    private fun show(new: LoadState<Paged<PlaylistPage>>) {
        more.cancel()
        state = new
    }

    fun loadMore() {
        val list = state.valueOrNull ?: return
        val token = list.more.askable ?: return
        state = LoadState.Loaded(list.copy(more = MoreState.Loading(token)))
        more.start({ api.playlistContinuation(token) }) { result ->
            val now = state.valueOrNull ?: return@start
            if (now.more != MoreState.Loading(token)) return@start
            state = LoadState.Loaded(
                result.fold(
                    { next ->
                        Paged(
                            now.content.copy(tracks = now.content.tracks + next.songs, continuation = next.continuation),
                            MoreState.of(next.continuation),
                        )
                    },
                    { now.copy(more = MoreState.Failed(token)) },
                ),
            )
        }
    }

    fun toggleSaved() {
        if (!state.isLoaded) return
        sender.tap()
    }
}
