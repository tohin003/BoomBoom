package io.github.boomboom.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import io.github.boomboom.logic.AppServices
import io.github.boomboom.logic.BackendState
import io.github.boomboom.logic.EqualizerStore
import io.github.boomboom.ui.browse.BrowseStart
import io.github.boomboom.ui.preview.PreviewMusicAPI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class SettingsScreenshots : PlacesTest() {
    /** One account, a song playing (so the audio line shows), a few downloads, a cache with songs in it. */
    private fun typical(others: List<io.github.boomboom.models.Account> = emptyList()): AppServices {
        val api = PreviewMusicAPI(delays = false)
        val services = build(api, cache = FakeCacheBackend(), others = others)
        api.catalog.songs.filter { it.isAvailable }.take(5).forEach { services.downloads.download(it) }
        services.equalizer.setFavorite(true, EqualizerStore.builtInPresets[3].id)
        services.player.play(api.catalog.songs[3])
        return services
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("settings.list").performScrollToNode(hasTestTag(tag))
    }

    @Test
    fun topToBottom() {
        showApp(typical(), BrowseStart.Settings)
        shot("settings_1_top")
        scrollTo("settings.downloadsSection")
        shot("settings_2_middle")
        repeat(4) { compose.onNodeWithTag("settings.list").performTouchInput { swipeUp() } }
        shot("settings_3_bottom")
    }

    @Test
    fun twoAccounts() {
        showApp(typical(listOf(second)), BrowseStart.Settings)
        shot("settings_two_accounts")
    }

    @Test
    fun threeAccounts() {
        showApp(typical(listOf(second, third)), BrowseStart.Settings)
        shot("settings_three_accounts")
    }

    @Test
    fun signedOut() {
        val api = PreviewMusicAPI(delays = false, signedIn = false)
        showApp(build(api, signedIn = false), BrowseStart.Settings)
        shot("settings_signed_out")
    }

    @Test
    fun signOutQuestion() {
        showApp(typical(listOf(second)), BrowseStart.Settings)
        compose.onAllNodesWithTag("settings.account.menu")[1].performClick()
        shot("settings_account_menu")
        compose.onNodeWithTag("settings.account.signOut").performClick()
        shot("settings_sign_out_question")
    }

    @Test
    fun presetDropDown() {
        val services = typical()
        services.equalizer.setFavorite(true, EqualizerStore.builtInPresets[6].id)
        services.equalizer.select(EqualizerStore.builtInPresets[6])
        showApp(services, BrowseStart.Settings)
        scrollTo("settings.preset")
        compose.onNodeWithTag("settings.preset").performClick()
        shot("settings_preset_menu")
    }

    @Test
    fun cacheQuestion() {
        showApp(typical(), BrowseStart.Settings)
        scrollTo("settings.cache.clear")
        compose.onNodeWithTag("settings.cache.clear").performClick()
        shot("settings_cache_question")
    }

    @Test
    fun theBrowserCouldNotLoad() {
        val api = PreviewMusicAPI(delays = false)
        val backend = FakeSessionBackend(count = 1)
        backend.state = BackendState.Failed("Couldn't reach the music service. Check your connection.")
        showApp(build(api, session = sessionOver(backend, api), cache = FakeCacheBackend()), BrowseStart.Settings)
        shot("settings_browser_failed")
    }

    @Test
    @Config(qualifiers = PHONE, fontScale = 1.3f)
    fun largeFont() {
        showApp(typical(listOf(second)), BrowseStart.Settings)
        shot("settings_font_1.3_top")
        scrollTo("settings.cache.limit")
        shot("settings_font_1.3_middle")
        repeat(6) { compose.onNodeWithTag("settings.list").performTouchInput { swipeUp() } }
        shot("settings_font_1.3_bottom")
    }
}

/** Settings' controls, tapped, checked against the stores and the navigator. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class SettingsBehaviourTests : PlacesTest() {
    private val api = PreviewMusicAPI(delays = false)

    private fun withAccounts(count: Int): Pair<AppServices, FakeSessionBackend> {
        val backend = FakeSessionBackend(count = count)
        val names = mapOf("profile-2" to second, "profile-3" to third).filterKeys { key -> backend.list.any { it.id == key } }
        val services = build(api, session = sessionOver(backend, api, names), cache = FakeCacheBackend())
        return services to backend
    }

    @Test
    fun anAccountRowSwitchesWithOneTap() {
        val (services, backend) = withAccounts(2)
        showApp(services, BrowseStart.Settings)
        val revision = services.session.revision
        compose.onNodeWithTag("settings.account").performClick()
        compose.waitForIdle()
        assertEquals(listOf("profile-2"), backend.switched)
        assertEquals("profile-2", services.session.accounts.first { it.isActive }.id)
        assertEquals(revision + 1, services.session.revision)
        // The row it switched from is now the one to tap.
        compose.onNodeWithTag("settings.account.active").assertIsDisplayed()
    }

    @Test
    fun addAnotherAccountGoesThroughTheNavigator() {
        val (services, backend) = withAccounts(2)
        val signIns = ArrayList<String?>()
        showApp(services, BrowseStart.Settings, onSignIn = { signIns.add(it) })
        compose.onNodeWithTag("settings.addAccount").performClick()
        assertEquals(1, backend.added)
        assertEquals(listOf<String?>("profile-new"), signIns)
    }

    @Test
    fun withThreeAccountsThereIsNoAddAccount() {
        val (services, _) = withAccounts(3)
        showApp(services, BrowseStart.Settings)
        assertEquals(3, compose.onAllNodesWithTag("settings.account.menu").fetchSemanticsNodes().size)
        compose.onNodeWithTag("settings.addAccount").assertDoesNotExist()
    }

    @Test
    fun signingOutAsksFirstAndOnlyThenSignsOut() {
        val (services, backend) = withAccounts(2)
        showApp(services, BrowseStart.Settings)
        // The second account's menu.
        compose.onAllNodesWithTag("settings.account.menu")[1].performClick()
        compose.onNodeWithTag("settings.account.signOut").performClick()
        compose.onNodeWithTag("settings.signOutQuestion").assertIsDisplayed()
        compose.onNodeWithText("Sign out of ${second.name}?").assertIsDisplayed()
        assertEquals(emptyList<String>(), backend.removed)

        compose.onNodeWithTag("settings.signOutQuestion.cancel").performClick()
        assertEquals(emptyList<String>(), backend.removed)
        assertEquals(2, services.session.accounts.size)

        compose.onAllNodesWithTag("settings.account.menu")[1].performClick()
        compose.onNodeWithTag("settings.account.signOut").performClick()
        compose.onNodeWithTag("settings.signOutQuestion.confirm").performClick()
        compose.waitForIdle()
        assertEquals(listOf("profile-2"), backend.removed)
        assertEquals(1, services.session.accounts.size)
    }

    @Test
    fun theBrowsersFailureOffersTryAgain() {
        val backend = FakeSessionBackend(count = 1)
        backend.state = BackendState.Failed("No connection")
        showApp(build(api, session = sessionOver(backend, api)), BrowseStart.Settings)
        compose.onNodeWithTag("settings.tryAgain").performClick()
        assertEquals(1, backend.reloads)
    }

    @Test
    fun thePresetDropDownListsFavouritesFirstAndChoosingOneAppliesIt() {
        val services = build(api)
        val presets = EqualizerStore.builtInPresets
        // Starred in this order; the menu lists them in the presets' own order, as the Sound screen's chips do.
        services.equalizer.setFavorite(true, presets[7].id)
        services.equalizer.setFavorite(true, presets[2].id)
        var opened = 0
        showApp(services, BrowseStart.Settings, onOpenEqualizer = { opened += 1 })
        compose.onNodeWithTag("settings.list").performScrollToNode(hasTestTag("settings.preset"))
        compose.onNodeWithTag("settings.preset.value", useUnmergedTree = true).assertTextEquals("Off")
        compose.onNodeWithTag("settings.preset").performClick()

        val names = compose.onAllNodesWithTag("settings.preset.option").fetchSemanticsNodes().map { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: ""
        }
        assertEquals(listOf(presets[2].name, presets[7].name), names.take(2))
        assertEquals(presets.size, names.size)

        val chosen = presets[4]
        compose.onAllNodesWithTag("settings.preset.option")[names.indexOf(chosen.name)].performClick()
        compose.waitForIdle()
        assertEquals(chosen.id, services.equalizer.selectedPresetId)
        assertTrue("choosing a preset turns the sound on", services.equalizer.settings.isEnabled)
        assertEquals(chosen.gains, services.equalizer.settings.gains)
        assertEquals("the Sound screen did not open", 0, opened)
        compose.onNodeWithTag("settings.preset.value", useUnmergedTree = true).assertTextEquals(chosen.name)

        // Off, from the same menu.
        compose.onNodeWithTag("settings.preset").performClick()
        compose.onNodeWithTag("settings.preset.off").performClick()
        assertFalse(services.equalizer.settings.isEnabled)
    }

    @Test
    fun theWayIntoTheSoundScreen() {
        var opened = 0
        showApp(build(api), BrowseStart.Settings, onOpenEqualizer = { opened += 1 })
        compose.onNodeWithTag("settings.list").performScrollToNode(hasTestTag("settings.equalizer"))
        compose.onNodeWithTag("settings.equalizer").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun downloadOverMobileDataTogglesOnItsFirstTapAndReachesTheStore() {
        val backend = FakeDownloadBackend()
        val services = build(api, downloads = backend)
        showApp(services, BrowseStart.Settings)
        compose.onNodeWithTag("settings.list").performScrollToNode(hasTestTag("settings.mobileData"))
        compose.onNodeWithTag("settings.mobileData").performClick()
        assertTrue(services.downloads.isMobileDataAllowed)
        assertTrue(backend.allowsDownloadsOnMobileData)
        compose.onNodeWithTag("settings.mobileData").performClick()
        assertFalse(services.downloads.isMobileDataAllowed)
        assertFalse(backend.allowsDownloadsOnMobileData)
    }

    @Test
    fun emptyingTheCacheAsksFirst() {
        val cache = FakeCacheBackend()
        showApp(build(api, cache = cache), BrowseStart.Settings)
        compose.onNodeWithTag("settings.list").performScrollToNode(hasTestTag("settings.cache.clear"))
        compose.onNodeWithTag("settings.cache.count").assertExists()
        compose.onNodeWithText("212").assertExists()

        compose.onNodeWithTag("settings.cache.clear").performClick()
        compose.onNodeWithTag("settings.clearCacheQuestion").assertIsDisplayed()
        assertEquals(0, cache.cleared)
        compose.onNodeWithTag("settings.clearCacheQuestion.cancel").performClick()
        assertEquals(0, cache.cleared)

        compose.onNodeWithTag("settings.cache.clear").performClick()
        compose.onNodeWithTag("settings.clearCacheQuestion.confirm").performClick()
        compose.waitForIdle()
        assertEquals(1, cache.cleared)
        compose.onNodeWithText("212").assertDoesNotExist()
        compose.onNodeWithTag("settings.cache.clear").assertIsNotEnabled()
    }

    @Test
    fun reportAProblemCallsTheNavigator() {
        var reports = 0
        showApp(build(api), BrowseStart.Settings, onReportProblem = { reports += 1 })
        compose.onNodeWithTag("settings.list").performScrollToNode(hasTestTag("settings.reportProblem"))
        compose.onNodeWithTag("settings.reportProblem").performClick()
        assertEquals(1, reports)
    }

    @Test
    fun theDownloadsRowOpensDownloads() {
        showApp(build(api), BrowseStart.Settings)
        compose.onNodeWithTag("settings.list").performScrollToNode(hasTestTag("settings.downloads"))
        compose.onNodeWithTag("settings.downloads").performClick()
        compose.onNodeWithTag("downloads").assertIsDisplayed()
    }
}
