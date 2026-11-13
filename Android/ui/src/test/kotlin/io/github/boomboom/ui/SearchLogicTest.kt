package io.github.boomboom.ui

import io.github.boomboom.logic.InMemoryKeyValueStore
import io.github.boomboom.models.MusicAPI
import io.github.boomboom.models.SearchFilter
import io.github.boomboom.models.SearchSuggestion
import io.github.boomboom.ui.browse.search.ResultsLoader
import io.github.boomboom.ui.browse.search.SearchModel
import io.github.boomboom.ui.preview.PreviewMusicAPI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.milliseconds

/** A data source whose suggestions take as long as a test says, and do not stop when cancelled (the worst case). */
private class SlowSuggestions(
    private val base: PreviewMusicAPI,
    private val millis: (String) -> Long,
) : MusicAPI by base {
    val asked = ArrayList<String>()

    override suspend fun searchSuggestions(input: String): List<SearchSuggestion> {
        asked.add(input)
        withContext(NonCancellable) { delay(millis(input)) }
        return base.searchSuggestions(input)
    }
}

/** The search logic on a virtual clock: the 180 ms pause, cancellation, and answers that arrive too late. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SearchLogicTest {
    private val scope = TestScope(StandardTestDispatcher())
    private val scheduler get() = scope.testScheduler

    private fun model(api: MusicAPI) = SearchModel(api, scope, InMemoryKeyValueStore(), debounce = 180.milliseconds)

    @Test
    fun keystrokesWithinThePauseAskOnceForTheLastWords() {
        val api = SlowSuggestions(PreviewMusicAPI(delays = false)) { 0 }
        val model = model(api)
        model.setText("l")
        scheduler.advanceTimeBy(100)
        model.setText("lo")
        scheduler.advanceTimeBy(100)
        model.setText("low")
        scheduler.advanceTimeBy(179)
        scheduler.runCurrent()
        assertEquals("nothing asked before the pause is over", emptyList<String>(), api.asked)
        assertTrue(model.suggestions.isLoading)

        scheduler.advanceTimeBy(2)
        scheduler.runCurrent()
        assertEquals(listOf("low"), api.asked)
        assertEquals("low", model.suggestions.answer.text)
        assertFalse(model.suggestions.isLoading)
        assertTrue(model.suggestions.visible("low").isNotEmpty())
    }

    @Test
    fun aSlowAnswerToOlderWordsIsDropped() {
        // "lo" takes 500 ms and ignores cancellation; "low" takes 50 ms.
        val api = SlowSuggestions(PreviewMusicAPI(delays = false)) { if (it == "lo") 500 else 50 }
        val model = model(api)
        model.setText("lo")
        scheduler.advanceTimeBy(181)
        scheduler.runCurrent()
        assertEquals(listOf("lo"), api.asked)

        model.setText("low")
        scheduler.advanceTimeBy(181 + 50)
        scheduler.runCurrent()
        assertEquals("low", model.suggestions.answer.text)

        // The old request finishes long after: it must not replace the newer answer.
        scheduler.advanceTimeBy(1000)
        scheduler.runCurrent()
        assertEquals(listOf("lo", "low"), api.asked)
        assertEquals("low", model.suggestions.answer.text)
        assertTrue(model.suggestions.visible("low").all { it !is SearchSuggestion.Query || it.query.text.startsWith("low") })
    }

    @Test
    fun whileTheNextAnswerIsOnItsWayEarlierWordSuggestionsThatStillFitStay() {
        val api = SlowSuggestions(PreviewMusicAPI(delays = false)) { 0 }
        val model = model(api)
        model.setText("lo")
        scheduler.advanceTimeBy(200)
        scheduler.runCurrent()
        model.setText("lon")
        // Asked for "lon" but not answered yet: the "lo" answers that begin with "lon" show, items do not.
        val shown = model.suggestions.visible("lon")
        assertTrue(shown.isNotEmpty())
        assertTrue(shown.all { it is SearchSuggestion.Query && it.query.text.startsWith("lon") })
    }

    @Test
    fun clearingTheFieldDropsWhatWasOnItsWay() {
        val api = SlowSuggestions(PreviewMusicAPI(delays = false)) { 0 }
        val model = model(api)
        model.setText("juniper")
        scheduler.advanceTimeBy(100)
        model.setText("")
        scheduler.advanceTimeBy(500)
        scheduler.runCurrent()
        assertEquals(emptyList<String>(), api.asked)
        assertFalse(model.suggestions.isLoading)
        assertTrue(model.suggestions.suggestions.isEmpty())
    }

    @Test
    fun submittingShowsResultsAndEditingGoesBackToSuggestions() {
        val model = model(PreviewMusicAPI(delays = false))
        model.setText("juniper")
        model.submit()
        scheduler.runCurrent()
        assertEquals(SearchModel.Mode.Results("juniper"), model.mode)
        assertTrue(model.results.phase is ResultsLoader.Phase.Loaded)
        assertEquals(listOf("juniper"), model.recents.all)

        model.setText("junipe")
        assertEquals(SearchModel.Mode.Suggestions, model.mode)
        assertEquals(ResultsLoader.Phase.Idle, model.results.phase)
    }

    @Test
    fun aFilterAppliesAndASecondTapClearsIt() {
        val model = model(PreviewMusicAPI(delays = false))
        model.submit("juniper")
        scheduler.runCurrent()
        model.select(SearchFilter.SONGS)
        scheduler.runCurrent()
        assertEquals(SearchFilter.SONGS, model.results.filter)
        assertTrue(model.results.phase is ResultsLoader.Phase.InList)
        model.select(SearchFilter.SONGS)
        scheduler.runCurrent()
        assertEquals(null, model.results.filter)
        assertTrue(model.results.phase is ResultsLoader.Phase.Loaded)
    }

    @Test
    fun aNewerSearchDropsTheAnswerToAnOlderOne() {
        val slowFirst = object : MusicAPI by PreviewMusicAPI(delays = false) {
            private val base = PreviewMusicAPI(delays = false)
            override suspend fun search(query: String, filter: SearchFilter?) =
                withContext(NonCancellable) {
                    delay(if (query == "old") 1000 else 10)
                    base.search(query, filter)
                }
        }
        val model = model(slowFirst)
        model.submit("old")
        scheduler.advanceTimeBy(100)
        model.submit("new")
        scheduler.advanceTimeBy(2000)
        scheduler.runCurrent()
        val phase = model.results.phase as ResultsLoader.Phase.Loaded
        assertEquals("new", phase.page.query)
    }
}
