package io.github.boomboom.logic

import io.github.boomboom.models.Account
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A stand-in for the hidden browser's profiles, with the same rules: the default profile always exists, an empty
 * profile is reused before a new one is made, a profile signed out goes (the default one is only emptied), and the
 * active one is replaced by the first other signed-in one.
 */
private class FakeBackend : SessionBackend {
    override var state: BackendState = BackendState.Ready
    override var onChange: (() -> Unit)? = null
    override val profileLimit = 3
    var ids = mutableListOf("default")
        private set
    private var signedIn = mutableSetOf<String>()
    var active = "default"
        private set

    /** What signing in to a profile in the sheet would do: the listener completes it, or gives up. */
    var nextSignInSucceeds = true
    private var counter = 0

    override val isSignedIn: Boolean get() = active in signedIn
    override val profiles: List<SessionProfile>
        get() = ids.map { SessionProfile(it, isSignedIn = it in signedIn, isActive = it == active) }

    override fun switchProfile(id: String) {
        if (id !in ids || id == active) return
        active = id
        onChange?.invoke()
    }

    override fun addProfile(): String? {
        ids.firstOrNull { it !in signedIn }?.let { return it }
        if (ids.size >= profileLimit) return null
        counter += 1
        val id = "p$counter"
        ids.add(id)
        onChange?.invoke()
        return id
    }

    override suspend fun removeProfile(id: String) {
        if (id !in ids) return
        signedIn.remove(id)
        if (id == active) {
            active = ids.firstOrNull { it != id && it in signedIn } ?: "default"
        }
        if (id != "default") ids.removeAll { it == id }
        onChange?.invoke()
    }

    override suspend fun didFinishSignIn(profile: String) {
        if (profile !in ids) return
        if (nextSignInSucceeds) {
            signedIn.add(profile)
            active = profile
        } else if (profile != "default" && profile != active) {
            ids.removeAll { it == profile }
        }
        onChange?.invoke()
    }

    override suspend fun signOut() = removeProfile(active)
    override fun reload() {}

    /** Signs a new account in the way the screens do it, and waits for the store to have it. */
    suspend fun add(store: SessionStore): String {
        val id = assertNotNull(store.beginAddingAccount())
        didFinishSignIn(id)
        return id
    }
}

/** Answers `account()` as whichever profile is active, the way the data client borrows the tab's cookies. */
private class AccountAPI : StubMusicAPI() {
    var backend: FakeBackend? = null

    override suspend fun account(): Account? {
        val backend = backend
        if (backend == null || !backend.isSignedIn) return null
        return Account(name = "Listener ${backend.active}", handle = "@${backend.active}")
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionStoreTests {
    private val storage = InMemoryKeyValueStore()
    private val backend = FakeBackend()
    private val api = AccountAPI().also { it.backend = backend }

    private fun TestScope.makeStore() = SessionStore(api, backend, storage, backgroundScope)

    /** Waits for the account fetches the store starts by itself. */
    private fun TestScope.settle() {
        runCurrent()
        wait(30)
    }

    @Test
    fun signingInTheFirstAccountUsesTheDefaultProfile() = runTest {
        val store = makeStore()
        assertTrue(!store.isSignedIn && store.accounts.isEmpty() && !store.canAddAccount)
        val id = backend.add(store)
        assertEquals("default", id)
        settle()
        assertTrue(store.isSignedIn)
        assertEquals(listOf("default"), store.accounts.map { it.id })
        assertEquals("Listener default", store.account?.name)
        assertTrue(store.canAddAccount)
    }

    @Test
    fun accountsCanBeAddedUpToTheLimitAndNoFurther() = runTest {
        val store = makeStore()
        backend.add(store)
        val second = backend.add(store)
        val third = backend.add(store)
        settle()
        assertEquals(3, store.accounts.size)
        assertEquals(third, store.accounts.firstOrNull { it.isActive }?.id)
        assertTrue(second != third)
        assertFalse(store.canAddAccount)
        assertNull(store.beginAddingAccount())
        assertEquals(3, backend.ids.size)
    }

    @Test
    fun switchingChangesTheActiveAccountAndRaisesTheRevision() = runTest {
        val store = makeStore()
        backend.add(store)
        val second = backend.add(store)
        settle()
        assertEquals("Listener $second", store.account?.name)
        val before = store.revision
        store.switchAccount("default")
        assertTrue(store.revision > before)
        assertEquals("Listener default", store.account?.name)
        assertEquals("default", store.accounts.firstOrNull { it.isActive }?.id)
        // The account not active is still shown by name.
        assertEquals("Listener $second", store.accounts.first { it.id == second }.account?.name)

        val again = store.revision
        store.switchAccount("default")
        assertEquals(again, store.revision, "switching to the active account changes nothing")
    }

    @Test
    fun signingOutTheActiveAccountMovesToAnotherOne() = runTest {
        val store = makeStore()
        backend.add(store)
        val second = backend.add(store)
        settle()
        val before = store.revision
        store.signOut(second)
        settle()
        assertTrue(store.revision > before)
        assertTrue(store.isSignedIn)
        assertEquals(listOf("default"), store.accounts.map { it.id })
        assertTrue(store.accounts.first().isActive)
        assertFalse(second in backend.ids)
    }

    @Test
    fun signingOutAnotherAccountLeavesTheActiveOne() = runTest {
        val store = makeStore()
        backend.add(store)
        val second = backend.add(store)
        settle()
        val before = store.revision
        store.signOut("default")
        settle()
        assertEquals(before, store.revision, "the active account did not change")
        assertEquals(listOf(second), store.accounts.map { it.id })
        assertEquals("Listener $second", store.account?.name)
        // The emptied default profile is used again for the next account rather than a new one.
        assertTrue(store.canAddAccount)
        assertEquals("default", store.beginAddingAccount())
    }

    @Test
    fun signingOutTheLastAccountLooksSignedOut() = runTest {
        val store = makeStore()
        backend.add(store)
        settle()
        store.signOut()
        assertTrue(!store.isSignedIn && store.accounts.isEmpty() && store.account == null)
    }

    @Test
    fun givingUpAnAddLeavesNoEmptyAccount() = runTest {
        val store = makeStore()
        backend.add(store)
        settle()
        val revision = store.revision
        val id = assertNotNull(store.beginAddingAccount())
        assertTrue(id != "default")
        backend.nextSignInSucceeds = false
        store.cancelAddingAccount(id)
        settle()
        assertFalse(id in backend.ids)
        assertEquals(listOf("default"), store.accounts.map { it.id })
        assertEquals(revision, store.revision)
        assertTrue(store.canAddAccount)
    }

    @Test
    fun givingUpTheFirstSignInLeavesTheSignedOutLook() = runTest {
        val store = makeStore()
        val id = assertNotNull(store.beginAddingAccount())
        backend.nextSignInSucceeds = false
        store.cancelAddingAccount(id)
        settle()
        assertTrue(!store.isSignedIn && store.accounts.isEmpty())
        assertEquals(listOf("default"), backend.ids)
    }

    @Test
    fun rememberedAccountsSurviveARestart() = runTest {
        val store = makeStore()
        backend.add(store)
        val second = backend.add(store)
        settle()
        store.switchAccount("default")
        settle()

        // The app starts again: the account not active is shown from what was remembered, before any fetch.
        api.backend = null
        val later = makeStore()
        assertEquals(listOf("default", second), later.accounts.map { it.id })
        assertEquals("Listener $second", later.accounts.first { it.id == second }.account?.name)
        assertEquals("Listener default", later.account?.name)
    }

    // Tests of my own.

    @Test
    fun anAnswerForTheAccountThatWasActiveBeforeASwitchIsDropped() = runTest {
        // The first account's answer is slow; the second account's comes first. When the slow one arrives it is
        // the answer to a question about an account that is no longer the active one.
        val slow = object : StubMusicAPI() {
            val answers = ArrayDeque(listOf(Account(name = "Late") to 3000L, Account(name = "Current") to 1000L))
            override suspend fun account(): Account? {
                val (answer, wait) = answers.removeFirst()
                kotlinx.coroutines.delay(wait)
                return answer
            }
        }
        val store = SessionStore(slow, backend, storage, backgroundScope)
        backend.add(store)
        runCurrent()                         // the first fetch is under way
        backend.add(store)                   // a second account takes over before it comes back
        wait(1500)
        assertEquals("Current", store.account?.name)
        wait(2500)
        assertEquals("Current", store.account?.name, "the late answer, for the first account, was dropped")
    }

    @Test
    fun aPreviewHasItsAccountsWithoutABrowser() = runTest {
        val preview = SessionStore.preview(
            StubMusicAPI(), backgroundScope, Account(name = "Me"), listOf(Account(name = "Other")),
        )
        assertTrue(preview.isSignedIn)
        assertEquals(listOf("preview-0", "preview-1"), preview.accounts.map { it.id })
        assertEquals(listOf(true, false), preview.accounts.map { it.isActive })
        assertEquals(3, preview.accountLimit)
        assertTrue(preview.canAddAccount)
        val signedOut = SessionStore.preview(StubMusicAPI(), backgroundScope, null)
        assertTrue(!signedOut.isSignedIn && signedOut.accounts.isEmpty() && !signedOut.canAddAccount)
    }
}
