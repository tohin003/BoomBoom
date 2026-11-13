package io.github.boomboom.service

import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.boomboom.logic.PlaybackStatus
import io.github.boomboom.logic.PlayerStore
import io.github.boomboom.logic.RepeatMode
import io.github.boomboom.models.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The player as the system sees it: [PlayerStore] dressed as a Media3 [Player], so the media session can draw the
 * notification and the lock-screen controls from it, and headset, Bluetooth and car buttons reach the store. The store
 * stays the one source of truth: every command here is passed straight on to it, and the state is read back from it.
 *
 * Runs on the main thread, as the store does.
 */
@OptIn(UnstableApi::class)
class StorePlayer(private val store: PlayerStore, scope: CoroutineScope) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val watching = listOf(
        scope.launch { store.state.collect { invalidateState() } },
        scope.launch { store.clock.state.map { it.duration }.distinctUntilChanged().collect { invalidateState() } },
    )

    init {
        // Song, playing or paused, a jump in time: what a lock screen shows.
        store.onChange = { invalidateState() }
    }

    override fun getState(): State {
        val now = store.state.value
        val builder = State.Builder()
            .setAudioAttributes(MUSIC)
            .setRepeatMode(
                when (now.repeatMode) {
                    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                },
            )
            .setShuffleModeEnabled(now.isShuffled)
        val index = now.currentIndex
        if (now.queue.isEmpty() || index == null) {
            return builder.setAvailableCommands(Player.Commands.Builder().add(Player.COMMAND_GET_TIMELINE).build())
                .setPlaybackState(Player.STATE_IDLE)
                .build()
        }

        val items = now.queue.mapIndexed { position, entry ->
            val known = if (position == index && store.clock.duration > 0) store.clock.duration else entry.song.duration
            MediaItemData.Builder(entry.id)
                .setMediaItem(mediaItem(entry.song, entry.id.toString()))
                .setDurationUs(known?.let { (it * 1_000_000).toLong() } ?: C.TIME_UNSET)
                .setIsSeekable(true)
                .build()
        }.toMutableList()
        // More of a radio comes when the last song is reached: a stand-in at the end lets "next" through to the store.
        if (index == now.queue.size - 1 && now.hasContinuation) {
            items += MediaItemData.Builder(MORE_UID)
                .setMediaItem(MediaItem.Builder().setMediaId(MORE_UID).build())
                .setIsPlaceholder(true)
                .build()
        }

        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_PREPARE, Player.COMMAND_GET_AUDIO_ATTRIBUTES,
        )
        if (now.hasNext) commands.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)

        val status = now.status
        builder.setAvailableCommands(commands.build())
            .setPlaylist(items)
            .setCurrentMediaItemIndex(index)
            .setPlayWhenReady(now.isPlayingOrAboutTo, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(
                when (status) {
                    PlaybackStatus.Idle, is PlaybackStatus.Failed -> Player.STATE_IDLE
                    PlaybackStatus.Loading, PlaybackStatus.Buffering -> Player.STATE_BUFFERING
                    PlaybackStatus.Playing, PlaybackStatus.Paused -> Player.STATE_READY
                },
            )
            .setIsLoading(status == PlaybackStatus.Loading || status == PlaybackStatus.Buffering)
            .setContentPositionMs { (store.clock.position * 1000).toLong() }
            .setContentBufferedPositionMs { (store.clock.bufferedFraction * store.clock.duration * 1000).toLong() }
        if (status is PlaybackStatus.Failed) {
            builder.setPlayerError(PlaybackException(status.message, null, PlaybackException.ERROR_CODE_UNSPECIFIED))
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (!playWhenReady) {
            store.pause()
        } else if (store.status.isFailure) {
            store.retry()
        } else {
            store.resume()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (store.status.isFailure) store.retry()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        // The system's "stop" (a notification swiped away) pauses; the queue stays for when the listener comes back.
        store.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        store.onChange = null
        watching.forEach { it.cancel() }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val seconds = if (positionMs == C.TIME_UNSET) 0.0 else positionMs / 1000.0
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> store.next()
            // The store decides between the start of this song and the one before, as the system would.
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> store.previous()
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD -> store.seek(seconds)
            else -> {
                val current = store.currentIndex
                val entry = store.queue.getOrNull(mediaItemIndex)
                when {
                    mediaItemIndex == current -> store.seek(seconds)
                    entry != null -> store.jump(entry.id)
                    mediaItemIndex == store.queue.size -> store.next()
                }
            }
        }
        return Futures.immediateVoidFuture()
    }

    private fun mediaItem(song: Song, id: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artistLine)
            .setAlbumTitle(song.album?.name)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        song.artwork.url(ARTWORK_PIXELS)?.let { metadata.setArtworkUri(Uri.parse(it)) }
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata.build()).build()
    }

    private companion object {
        const val MORE_UID = "boom:more"
        const val ARTWORK_PIXELS = 600
        val MUSIC: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
    }
}
