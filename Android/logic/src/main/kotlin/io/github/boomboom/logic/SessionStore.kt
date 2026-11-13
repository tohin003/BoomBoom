package io.github.boomboom.logic

import io.github.boomboom.models.Account
import io.github.boomboom.models.MusicAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** How far along the hidden YouTube Music tab is. */
sealed class BackendState {
    /** The tab is loading. Browsing already works; the first song waits for it. */
    data object Starting : BackendState()

    data object Ready : BackendState()

    /** The tab could not load (no connection). The text is for the listener. */
    data class Failed(val message: String) : BackendState()
}

/** One browser profile: a Google account kept apart from the others (or a profile not signed in yet). */
data class SessionProfile(
    val id: String,
    val isSignedIn: Boolean,
    val isActive: Boolean,
)

/** The sign-in and the hidden tab behind it, as [SessionStore] drives them. The hidden browser implements it. */
interface SessionBackend {
    val state: BackendState

    /** Whether the active profile is signed in. */
    val isSignedIn: Boolean

    /** Called whenever [state], [isSignedIn] or the profiles change (a switch included). */
    var onChange: (() -> Unit)?

    /** Every profile, in a stable order; exactly one is active. */
    val profiles: List<SessionProfile>

    /** How many profiles there may be. */
    val profileLimit: Int

    /** Makes another profile the active one. The hidden tab reloads as that account. */
    fun switchProfile(id: String)

    /** A profile to sign another account in to (not switched to). Null when all are taken. */
    fun addProfile(): String?

    /** Signs a profile out and drops it. When it was the active one, another signed-in one takes over. */
    suspend fun removeProfile(id: String)

    /** The sign-in sheet for this profile closed, signed in or not. */
    suspend fun didFinishSignIn(profile: String)

    /** Signs the active profile out. */
    suspend fun signOut()

    /** Loads the hidden tab again (after a failure, or when the connection is back). */
    fun reload()
}

/** A signed-in Google account as the account switcher shows it. */
data class SessionAccount(
    /** The profile the account lives in. Not the Google account's own id, which the app never sees. */
    val id: String,
    /** Name, handle and picture: fetched for the active account, remembered from earlier for the others. */
    val account: Account?,
    val isActive: Boolean,
)

/** What the sign-in screens read. It changes with an account, never with the clock. */
data class SessionState(
    /** Whether the active account is signed in. */
    val isSignedIn: Boolean = false,
    /** The active account's name and picture, once fetched. */
    val account: Account? = null,
    val backendState: BackendState = BackendState.Ready,
    /**
     * Goes up each time the active account changes (a sign-in, a sign-out or a switch); screens that show personal
     * data reload when it does.
     */
    val revision: Int = 0,
    /** Every signed-in account, in the order they were added. */
    val accounts: List<SessionAccount> = emptyList(),
    /** Whether another account can be added. */
    val canAddAccount: Boolean = false,
    /** How many accounts the app keeps at most. */
    val accountLimit: Int = 1,
)

/**
 * The listener's Google accounts in the app: up to three, one of them active. The store lives on one thread, the one
 * [scope] runs on; the backend calls it back on that thread.
 */
class SessionStore(
    private val api: MusicAPI,
    private val backend: SessionBackend?,
    private val storage: KeyValueStore,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val isSignedIn: Boolean get() = _state.value.isSignedIn
    val account: Account? get() = _state.value.account
    val backendState: BackendState get() = _state.value.backendState
    val revision: Int get() = _state.value.revision
    val accounts: List<SessionAccount> get() = _state.value.accounts
    val canAddAccount: Boolean get() = _state.value.canAddAccount
    val accountLimit: Int get() = _state.value.accountLimit

    private fun update(change: SessionState.() -> SessionState) {
        _state.value = _state.value.change()
    }

    /** The profile that was active at the last look: a change of it is a change of account. */
    private var activeProfileId: String? = backend?.profiles?.firstOrNull { it.isActive }?.id

    /** Each signed-in profile's account as last fetched, so the switcher can show the ones not active. */
    private var remembered: Map<String, Account> =
        storage.getString(REMEMBERED_KEY)?.let { decodeOrNull(rememberedSerializer, it) } ?: emptyMap()

    init {
        val signedIn = backend?.isSignedIn ?: false
        update {
            copy(
                isSignedIn = signedIn,
                backendState = backend?.state ?: BackendState.Ready,
                accountLimit = backend?.profileLimit ?: 1,
                account = activeProfileId?.takeIf { signedIn }?.let { remembered[it] },
            )
        }
        backend?.onChange = { backendDidChange() }
        rebuildAccounts()
        if (signedIn) scope.launch { refreshAccount() }
    }

    suspend fun refreshAccount() {
        val profile = activeProfileId
        if (!isSignedIn || profile == null) {
            update { copy(account = null) }
            return
        }
        val asked = revision
        val fetched = attempt { api.account() } ?: return
        // The answer belongs to whichever account was active when it was asked for.
        if (asked != revision || profile != activeProfileId) return
        update { copy(account = fetched) }
        remember(fetched, profile)
        rebuildAccounts()
    }

    /** Signs the active account out. */
    suspend fun signOut() {
        backend?.signOut()
        backendDidChange()
    }

    /** Signs one account out, the active one or another. */
    suspend fun signOut(accountId: String) {
        backend?.removeProfile(accountId)
        backendDidChange()
    }

    /** Makes another signed-in account the active one. Returns once its name and picture are in. */
    suspend fun switchAccount(id: String) {
        if (backend == null || id == activeProfileId || accounts.none { it.id == id }) return
        backend.switchProfile(id)
        backendDidChange()
        refreshAccount()
    }

    /** A profile for the sign-in sheet to sign another account in to. Null when no more can be added. */
    fun beginAddingAccount(): String? {
        if (!(canAddAccount || !isSignedIn)) return null
        val id = backend?.addProfile()
        backendDidChange()
        return id
    }

    /** The sign-in sheet for that profile came back from Google: the new account becomes the active one. */
    fun finishAddingAccount(id: String) {
        scope.launch {
            backend?.didFinishSignIn(id)
            backendDidChange()
        }
    }

    /** The listener closed the sign-in sheet without signing in: an empty new profile is dropped. */
    fun cancelAddingAccount(id: String) {
        // The browser looks at the profile itself: a sign-in that went through after all is kept.
        finishAddingAccount(id)
    }

    /** Loads the hidden tab again after a failure. */
    fun retryBackend() {
        backend?.reload()
    }

    /** The one place the store follows the backend. */
    private fun backendDidChange() {
        val backend = backend ?: return
        val active = backend.profiles.firstOrNull { it.isActive }?.id
        val accountChanged = active != activeProfileId || backend.isSignedIn != isSignedIn
        activeProfileId = active
        val signedIn = backend.isSignedIn
        update { copy(backendState = backend.state, isSignedIn = signedIn) }
        if (accountChanged) {
            update {
                copy(revision = revision + 1, account = if (signedIn) active?.let { remembered[it] } else null)
            }
            if (signedIn) scope.launch { refreshAccount() }
        }
        rebuildAccounts()
    }

    private fun rebuildAccounts() {
        val backend = backend ?: return
        val profiles = backend.profiles
        val signedIn = profiles.filter { it.isSignedIn }
        // Accounts no longer signed in are forgotten, names and pictures with them.
        val kept = remembered.filterKeys { id -> signedIn.any { it.id == id } }
        if (kept.size != remembered.size) {
            remembered = kept
            saveRemembered()
        }
        update {
            copy(
                accounts = signedIn.map { profile ->
                    SessionAccount(
                        id = profile.id,
                        account = if (profile.isActive) account ?: remembered[profile.id] else remembered[profile.id],
                        isActive = profile.isActive,
                    )
                },
                accountLimit = backend.profileLimit,
                canAddAccount = isSignedIn && signedIn.size < backend.profileLimit,
            )
        }
    }

    private fun remember(account: Account, profile: String) {
        if (remembered[profile] == account) return
        remembered = remembered + (profile to account)
        saveRemembered()
    }

    private fun saveRemembered() {
        storage.putString(REMEMBERED_KEY, BoomJson.encodeToString(rememberedSerializer, remembered))
    }

    companion object {
        private const val REMEMBERED_KEY = "session.accounts"
        private val rememberedSerializer = MapSerializer(String.serializer(), Account.serializer())

        /** For previews: a fixed state without a browser behind it. */
        fun preview(
            api: MusicAPI,
            scope: CoroutineScope,
            previewAccount: Account?,
            otherAccounts: List<Account> = emptyList(),
        ): SessionStore {
            val store = SessionStore(api, backend = null, storage = InMemoryKeyValueStore(), scope = scope)
            store.update {
                copy(
                    isSignedIn = previewAccount != null,
                    account = previewAccount,
                    backendState = BackendState.Ready,
                    accountLimit = 3,
                    accounts = if (previewAccount == null) {
                        emptyList()
                    } else {
                        listOf(SessionAccount("preview-0", previewAccount, isActive = true)) +
                            otherAccounts.mapIndexed { index, other ->
                                SessionAccount("preview-${index + 1}", other, isActive = false)
                            }
                    },
                )
            }
            store.update { copy(canAddAccount = isSignedIn && accounts.size < accountLimit) }
            return store
        }
    }
}
