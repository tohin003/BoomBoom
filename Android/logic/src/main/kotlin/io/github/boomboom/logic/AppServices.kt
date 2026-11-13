package io.github.boomboom.logic

import io.github.boomboom.models.Account
import io.github.boomboom.models.MusicAPI
import kotlinx.coroutines.CoroutineScope

/**
 * Everything a screen may need, handed down the screens' tree (a composition local in the app).
 */
class AppServices(
    /** YouTube Music's data: search, pages, the library. */
    val api: MusicAPI,
    val player: PlayerStore,
    val equalizer: EqualizerStore,
    val session: SessionStore,
    val cache: CacheStore,
    /** The songs, albums and playlists the listener keeps on the phone. */
    val downloads: DownloadStore,
) {
    companion object {
        /**
         * A silent, self-contained set for previews and UI work: the given data source (canned data), a player that
         * keeps time without sound, the Kotlin response curve, and settings kept in memory apart from the real app's.
         * [otherAccounts] fills the account switcher with signed-in accounts that are not active. Downloads
         * "finish" at once, in memory.
         */
        fun preview(
            api: MusicAPI,
            scope: CoroutineScope,
            account: Account? = null,
            otherAccounts: List<Account> = emptyList(),
            time: Clock = Clock.System,
        ): AppServices {
            val storage = InMemoryKeyValueStore()
            return AppServices(
                api = api,
                player = PlayerStore(api, SimulatedEngine(scope, time), storage, scope, time),
                equalizer = EqualizerStore(KotlinSoundMath, storage, scope, time),
                session = SessionStore.preview(api, scope, account, otherAccounts),
                cache = CacheStore(backend = null, storage = storage),
                downloads = DownloadStore(backend = null, storage = storage, scope = scope, time = time),
            )
        }
    }
}
