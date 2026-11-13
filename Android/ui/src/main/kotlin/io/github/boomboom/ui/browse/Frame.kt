package io.github.boomboom.ui.browse

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.boomboom.ui.design.BarIconButton
import io.github.boomboom.ui.design.BoomColor
import io.github.boomboom.ui.design.BoomFont
import io.github.boomboom.ui.design.BoomMetrics
import io.github.boomboom.ui.design.ErrorView
import io.github.boomboom.ui.design.LoadingStyle
import io.github.boomboom.ui.design.LoadingView
import io.github.boomboom.ui.design.LocalAppServices
import io.github.boomboom.ui.design.MessageView
import io.github.boomboom.ui.design.PillButton
import io.github.boomboom.ui.design.PillStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The height of a pushed page's bar (back button and title), below the status bar. */
val PageBarHeight = 56.dp

/**
 * A pushed page: an optional backdrop (drawn behind the bar too), the bar with the back button and the page's name
 * (shown once the page's own big title has scrolled away), and the content below the bar, never under it.
 */
@Composable
fun PageFrame(
    title: String,
    isTitleShown: Boolean,
    modifier: Modifier = Modifier,
    backdrop: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val router = LocalShellRouter.current
    Box(
        modifier
            .fillMaxSize()
            .background(BoomColor.background),
    ) {
        backdrop?.invoke(this)
        Column(Modifier.fillMaxSize()) {
            PageTopBar(title, isTitleShown) { router.back() }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { content() }
        }
    }
}

/** The bar of a pushed page: clear over the page's header, solid once the title shows. */
@Composable
fun PageTopBar(title: String, isTitleShown: Boolean, onBack: () -> Unit) {
    val background by animateColorAsState(if (isTitleShown) BoomColor.background else Color.Transparent, label = "bar")
    val titleAlpha by animateFloatAsState(if (isTitleShown) 1f else 0f, label = "title")
    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .statusBarsPadding()
            .height(PageBarHeight)
            .padding(start = 4.dp, end = BoomMetrics.margin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack, Modifier.testTag("page.back"))
        Spacer(Modifier.width(8.dp))
        Text(
            title, style = BoomFont.sectionTitle, color = BoomColor.textPrimary, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .graphicsLayer { alpha = titleAlpha },
        )
    }
}

/** True once the list has scrolled more than [threshold] past its start. */
@Composable
fun LazyListState.scrolledPast(threshold: Dp): State<Boolean> {
    val pixels = with(LocalDensity.current) { threshold.toPx() }
    return remember(this, pixels) {
        derivedStateOf { firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > pixels }
    }
}

/** How far the list has scrolled, in pixels, while its first item is in view; a large number after. */
fun LazyListState.headerOffset(): Float =
    if (firstVisibleItemIndex == 0) firstVisibleItemScrollOffset.toFloat() else 100_000f

/** A page's big title at the top of its content ("Moods & genres", "Charts"). */
@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = BoomFont.pageTitle, color = BoomColor.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(horizontal = BoomMetrics.margin)
            .semantics { heading() },
    )
}

/** Skeletons, an error with Retry, or the content, from a [LoadState]. */
@Composable
fun <T> LoadStateView(
    state: LoadState<T>,
    retry: () -> Unit,
    style: LoadingStyle = LoadingStyle.SHELVES,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        LoadState.Loading -> Column(Modifier.fillMaxSize()) { LoadingView(style, Modifier.padding(top = 8.dp)) }
        is LoadState.Failed -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            PageErrorView(state.message, retry)
        }
        is LoadState.Loaded -> content(state.value)
    }
}

/**
 * A page that could not load: the message and Retry, and a way to the songs on the phone when there are some (the
 * likely reason is no connection).
 */
@Composable
fun PageErrorView(message: String, retry: (() -> Unit)?) {
    val services = LocalAppServices.current
    val router = LocalShellRouter.current
    val downloads by services.downloads.state.collectAsState()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ErrorView(message, retry = retry)
        if (downloads.downloaded.isNotEmpty()) {
            PillButton(
                "Play your downloads", { router.push(BrowseRoute.Downloads) },
                Modifier.testTag("error.downloads"), style = PillStyle.FILLED, icon = Icons.Filled.DownloadForOffline,
            )
        }
    }
}

/**
 * The row at the end of a list that loads more when it comes near the screen: a spinner while the next part is on its
 * way, Retry when asking for it failed.
 */
@Composable
fun LoadMoreRow(more: MoreState, load: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("loadMore"),
        contentAlignment = Alignment.Center,
    ) {
        when (more) {
            is MoreState.Loading -> CircularProgressIndicator(
                color = BoomColor.textSecondary, strokeWidth = 2.dp,
                modifier = Modifier
                    .padding(8.dp)
                    .height(24.dp)
                    .width(24.dp),
            )
            is MoreState.Failed -> PillButton("Retry", load, Modifier.testTag("loadMore.retry"), contentDescription = "Retry, loads more")
            is MoreState.Ready, MoreState.End -> Unit
        }
    }
    // On appearing, and for each new token while on screen. A failed part is asked again when the row comes back.
    LaunchedEffect(more.tokenOrNull) { load() }
}

/**
 * Pull to refresh around a page. [refresh] returns the job of the reload; the spinner shows until it is done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshBox(refresh: () -> Job, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            scope.launch {
                refresh().join()
                isRefreshing = false
            }
        },
        modifier = modifier.fillMaxSize(),
        content = content,
    )
}

/** A screen another part of the work fills later (Library, Downloads, Settings). */
@Composable
fun ComingScreen(title: String, showsBack: Boolean) {
    val router = LocalShellRouter.current
    Column(
        Modifier
            .fillMaxSize()
            .background(BoomColor.background),
    ) {
        if (showsBack) {
            PageTopBar(title, isTitleShown = true) { router.back() }
        } else {
            Text(
                title, style = BoomFont.pageTitle, color = BoomColor.textPrimary,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = BoomMetrics.margin, vertical = 12.dp)
                    .semantics { heading() },
            )
        }
        MessageView(
            "Coming soon", message = "$title is on its way in a later version.", icon = Icons.Outlined.Construction,
            modifier = Modifier.testTag("coming"),
        )
    }
}
