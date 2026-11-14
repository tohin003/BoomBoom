package io.github.boomboom.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.boomboom.logic.AppServices
import io.github.boomboom.logic.Clock
import io.github.boomboom.ui.design.BoomNavigator
import io.github.boomboom.ui.design.BoomTheme
import io.github.boomboom.ui.design.DownloadCollector
import io.github.boomboom.ui.design.LocalAppScope
import io.github.boomboom.ui.design.LocalAppServices
import io.github.boomboom.ui.design.LocalBoomNavigator
import io.github.boomboom.ui.design.LocalDownloadCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/** The tabs at the foot of the full player. */
enum class NowPlayingPanel(val title: String, val tag: String) {
    UP_NEXT("UP NEXT", "upNext"),
    LYRICS("LYRICS", "lyrics"),
    RELATED("RELATED", "related"),
}

/**
 * The full player, laid over everything (the tab bar too). Put it at the app's root above [io.github.boomboom.ui.BoomApp];
 * it slides itself up when [isExpanded] turns true, and down when the listener drags it away, taps the chevron or
 * presses Back (each of which calls [onCollapse], so the app can set [isExpanded] back). While collapsed it draws
 * nothing and lets every touch through.
 *
 * It brings its own look and composition locals, since it sits beside the browsing frame, not inside it.
 *
 * @param navigator What the player asks the app for: a page ("Go to album", the artist's name, a related card), the
 *   equaliser, "Save to playlist", the sign-in. The player collapses itself before a page opens.
 * @param time "Now" in the stores' terms, for the sleep timer. Tests pass their scheduler's clock.
 * @param workContext Added to every coroutine the player starts; tests pass a test dispatcher.
 */
@Composable
fun NowPlayingOverlay(
    services: AppServices,
    isExpanded: Boolean,
    onCollapse: () -> Unit,
    navigator: BoomNavigator,
    modifier: Modifier = Modifier,
    time: Clock = Clock.System,
    workContext: CoroutineContext = EmptyCoroutineContext,
) {
    val scope = rememberCoroutineScope { workContext }
    val model = remember { OverlayModel(scope) }
    val collector = remember { DownloadCollector(scope) }
    model.requestCollapse = onCollapse
    // [isExpanded] is the truth whenever the player comes to rest: if the app asks for it again while it is still
    // sliding away (both in one frame), it comes back up once down.
    val isHidden = model.presentation == OverlayModel.Presentation.Hidden
    LaunchedEffect(isExpanded, isHidden) { if (isExpanded) model.expand() else model.collapse() }

    // Leaving for a page: down first, then the app opens it.
    val playerNavigator = remember(navigator, model) {
        navigator.copy(open = { endpoint ->
            model.dismiss()
            navigator.open(endpoint)
        })
    }
    BoomTheme {
        CompositionLocalProvider(
            LocalAppServices provides services,
            LocalBoomNavigator provides playerNavigator,
            LocalAppScope provides scope,
            LocalDownloadCollector provides collector,
            LocalPlayerTime provides time,
            LocalOverlayModel provides model,
        ) {
            OverlayStage(model, modifier)
        }
    }
}

internal val LocalOverlayModel = staticCompositionLocalOf<OverlayModel> { error("NowPlayingOverlay provides it") }

/**
 * Where the overlay is and what it shows. Its offset is read only while drawing (a graphics layer), so following a
 * finger redraws one layer and does not recompose the player.
 */
internal class OverlayModel(private val scope: CoroutineScope) {
    enum class Axis { HORIZONTAL, VERTICAL }

    /** Where the player is. Changed only through [move]. */
    sealed class Presentation {
        /** Not in the hierarchy; touches pass through. */
        data object Hidden : Presentation()

        /** Just put in the hierarchy at the bottom, waiting for its first measure; then it slides up. */
        data object Mounting : Presentation()

        /** Up, or springing up (after opening, or after a drag that did not go far enough). */
        data object Shown : Presentation()

        /** A finger is on the player. [Axis.HORIZONTAL] is a sideways touch, which does not move it. */
        data class Dragging(val axis: Axis) : Presentation()

        /**
         * Sliding down. [id] tells this slide from a later one, so only its own end takes the player away; [closing]
         * is the panel that was up, cleared when the slide ends.
         */
        data class Dismissing(val id: Int, val closing: NowPlayingPanel?) : Presentation()
    }

    var presentation by mutableStateOf<Presentation>(Presentation.Hidden)
        private set

    /** 0 when fully up; [travel] when fully down. Pixels. */
    val offset = Animatable(4000f)

    /** The overlay's height and a little more: how far down "fully down" is. */
    var travel by mutableFloatStateOf(0f)
        private set

    var panel by mutableStateOf<NowPlayingPanel?>(null)
        private set

    var requestCollapse: () -> Unit = {}
    private var dismissals = 0

    val isRendered: Boolean get() = presentation != Presentation.Hidden

    /** False while it slides away. */
    val isInteractive: Boolean
        get() = when (presentation) {
            Presentation.Hidden, is Presentation.Dismissing -> false
            Presentation.Mounting, Presentation.Shown, is Presentation.Dragging -> true
        }

    private fun move(next: Presentation) {
        if (presentation != next) presentation = next
    }

    fun measured(height: Float) {
        if (height <= 0f || height == travel) return
        travel = height
        when (presentation) {
            Presentation.Hidden -> scope.launch { offset.snapTo(height) }
            Presentation.Mounting -> {
                move(Presentation.Shown)
                scope.launch {
                    offset.snapTo(height)
                    offset.animateTo(0f, presentSpring)
                }
            }
            else -> Unit
        }
    }

    fun expand() {
        when (presentation) {
            Presentation.Hidden -> {
                if (travel > 0f) {
                    move(Presentation.Shown)
                    scope.launch {
                        offset.snapTo(travel)
                        offset.animateTo(0f, presentSpring)
                    }
                } else {
                    move(Presentation.Mounting)
                }
            }
            // Caught on its way down: back up from where it is.
            is Presentation.Dismissing -> {
                move(Presentation.Shown)
                scope.launch { offset.animateTo(0f, presentSpring) }
            }
            Presentation.Mounting, Presentation.Shown, is Presentation.Dragging -> Unit
        }
    }

    fun collapse(velocity: Float = 0f) {
        when (presentation) {
            Presentation.Hidden, is Presentation.Dismissing -> return
            Presentation.Mounting -> {
                // Never got to slide up: nothing to animate.
                finishDismissal(panel)
                return
            }
            Presentation.Shown, is Presentation.Dragging -> Unit
        }
        dismissals += 1
        val id = dismissals
        move(Presentation.Dismissing(id, panel))
        scope.launch {
            try {
                if (velocity > 0f) {
                    offset.animateTo(travel, spring(dampingRatio = 1f, stiffness = 260f), initialVelocity = velocity)
                } else {
                    offset.animateTo(travel, tween(380))
                }
            } catch (cancelled: CancellationException) {
                // Caught by a new expand: that one decides.
            }
            val now = presentation
            if (now is Presentation.Dismissing && now.id == id) finishDismissal(now.closing)
        }
    }

    private fun finishDismissal(closing: NowPlayingPanel?) {
        move(Presentation.Hidden)
        // A panel asked for while the player was on its way down is kept for the next time it opens.
        if (panel == closing) panel = null
    }

    /** Closes the player from inside it (the chevron, Back, or before going to a page). */
    fun dismiss() {
        requestCollapse()
        collapse()
    }

    fun showPanel(tab: NowPlayingPanel?) {
        if (panel != tab) panel = tab
    }

    // Dragging the player down

    fun dragChanged(translation: Offset) {
        when (presentation) {
            Presentation.Shown -> move(
                Presentation.Dragging(if (abs(translation.x) > abs(translation.y)) Axis.HORIZONTAL else Axis.VERTICAL),
            )
            is Presentation.Dragging -> Unit
            // On its way down (or not up yet): a finger does not catch it.
            Presentation.Hidden, Presentation.Mounting, is Presentation.Dismissing -> return
        }
        if (presentation != Presentation.Dragging(Axis.VERTICAL)) return
        val dy = translation.y
        // Upward, it gives a little and resists.
        val target = if (dy >= 0) dy else -(-dy).pow(0.7f)
        scope.launch { offset.snapTo(target) }
    }

    fun dragEnded(translation: Offset, velocity: Float) {
        // A quick flick can end before any change was reported: it starts here.
        if (presentation == Presentation.Shown) dragChanged(translation)
        val dragging = presentation as? Presentation.Dragging ?: return
        if (dragging.axis != Axis.VERTICAL) {
            move(Presentation.Shown)
            return
        }
        if (translation.y > travel * 0.22f || (translation.y > travel * 0.1f && velocity > 1500f)) {
            requestCollapse()
            collapse(velocity = max(velocity, 0f))
        } else {
            settleUp()
        }
    }

    /** The system took the touch away mid-drag: the player goes back up. */
    fun dragCancelled() {
        val dragging = presentation as? Presentation.Dragging ?: return
        if (dragging.axis == Axis.VERTICAL) settleUp() else move(Presentation.Shown)
    }

    private fun settleUp() {
        move(Presentation.Shown)
        scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) }
    }

    companion object {
        val presentSpring = spring<Float>(dampingRatio = 0.9f, stiffness = 420f)
    }
}

/**
 * Moves the player with the model's offset, dims what is behind it, and turns a downward drag that no control inside
 * took into the player's own drag down. The drag is read here, on a layer that does not move, so the finger's
 * position is not thrown off by the player moving under it.
 */
@Composable
private fun OverlayStage(model: OverlayModel, modifier: Modifier) {
    val extraTravel = with(LocalDensity.current) { 40.dp.toPx() }
    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { model.measured(it.height + extraTravel) },
    ) {
        if (!model.isRendered) return@Box
        BackHandler(enabled = model.isInteractive) {
            if (model.panel != null) model.showPanel(null) else model.dismiss()
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val travel = max(model.travel, 1f)
                    alpha = 0.55f * (1f - model.offset.value / travel).coerceIn(0f, 1f)
                }
                .background(Color.Black),
        )
        Box(
            Modifier
                .fillMaxSize()
                .testTag("player.stage")
                .pointerInput(model) { detectCollapseDrag(model) }
                .graphicsLayer { translationY = model.offset.value },
        ) {
            PlayerScreen()
        }
    }
}

/**
 * The player's drag down: any drag that starts downward or upward and that no control under the finger has taken
 * (the scrubber, a sideways cover swipe, a list in a panel) moves the player. Released far enough, it closes; a small
 * drag springs back. While the player slides away every touch on it is swallowed, so nothing inside fires.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectCollapseDrag(model: OverlayModel) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        // The touch-down is seen before the controls see it, to swallow it while the player slides away; otherwise
        // it is left alone and the rest of the touch is read after the controls have had their say.
        val first = awaitPointerEvent(PointerEventPass.Initial)
        val down = first.changes.firstOrNull { it.changedToDownIgnoreConsumed() } ?: return@awaitEachGesture
        if (!model.isInteractive) {
            down.consume()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) return@awaitEachGesture
            }
        }
        awaitPointerEvent(PointerEventPass.Main)    // the same touch-down, after the controls
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var total = Offset.Zero
        var owned = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                if (owned) {
                    tracker.addPosition(change.uptimeMillis, change.position)
                    model.dragEnded(total, tracker.calculateVelocity().y)
                }
                break
            }
            if (change.isConsumed) {
                // A control under the finger took it (or took it back).
                if (owned) model.dragCancelled()
                break
            }
            total += change.positionChange()
            tracker.addPosition(change.uptimeMillis, change.position)
            if (!owned) {
                if (total.getDistance() < slop) continue
                if (abs(total.x) > abs(total.y)) break    // sideways: not ours
                owned = true
            }
            change.consume()
            model.dragChanged(total)
        }
    }
}
