package io.github.boomboom.logic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The audio kept on the phone so songs start at once and replay without the network. */
interface AudioCacheBackend {
    suspend fun usedBytes(): Long
    suspend fun songCount(): Int
    suspend fun clear()
    var limitBytes: Long
}

data class CacheState(
    val usedBytes: Long = 0,
    val songCount: Int = 0,
    /** The most the cache may hold; the least recently played songs go first. */
    val limitBytes: Long = DEFAULT_LIMIT_BYTES,
) {
    companion object {
        const val DEFAULT_LIMIT_BYTES = 2_000_000_000L
    }
}

class CacheStore(
    private val backend: AudioCacheBackend?,
    private val storage: KeyValueStore,
) {
    private val _state = MutableStateFlow(CacheState(limitBytes = storage.getLong(LIMIT_KEY) ?: CacheState.DEFAULT_LIMIT_BYTES))
    val state: StateFlow<CacheState> = _state.asStateFlow()

    val usedBytes: Long get() = _state.value.usedBytes
    val songCount: Int get() = _state.value.songCount

    /** The most the cache may hold; the least recently played songs go first. */
    var limitBytes: Long
        get() = _state.value.limitBytes
        set(value) {
            _state.value = _state.value.copy(limitBytes = value)
            backend?.limitBytes = value
            storage.putLong(LIMIT_KEY, value)
        }

    init {
        backend?.limitBytes = limitBytes
    }

    suspend fun refresh() {
        val backend = backend ?: return
        val used = backend.usedBytes()
        val count = backend.songCount()
        _state.value = _state.value.copy(usedBytes = used, songCount = count)
    }

    suspend fun clear() {
        backend?.clear()
        refresh()
    }

    companion object {
        val LIMIT_CHOICES: List<Long> = listOf(500L, 1000L, 2000L, 5000L, 10000L).map { it * 1_000_000 }
        private const val LIMIT_KEY = "cache.limitBytes"
    }
}
