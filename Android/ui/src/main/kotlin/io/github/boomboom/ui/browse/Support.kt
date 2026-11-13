package io.github.boomboom.ui.browse

import io.github.boomboom.models.MusicAPIError
import io.github.boomboom.models.Shelf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// The states a screen's requests go through, kept free of Compose so the page models can be driven by plain tests.

/** Where a request for a screen's content stands. */
sealed class LoadState<out T> {
    data object Loading : LoadState<Nothing>()
    data class Failed(val message: String) : LoadState<Nothing>()
    data class Loaded<T>(val value: T) : LoadState<T>()

    val valueOrNull: T? get() = (this as? Loaded<T>)?.value
    val isLoaded: Boolean get() = this is Loaded
}

/** Words for the listener when a request fails. The error's own text is for the log only. */
fun userMessage(error: Throwable): String = when (error) {
    is MusicAPIError.Network -> "Couldn't connect. Check your connection and try again."
    MusicAPIError.SignedOut -> "Sign in to see this."
    MusicAPIError.Unauthorized -> "Your sign-in has run out. Sign in again to continue."
    is MusicAPIError.Unreadable -> "Couldn't read this page right now."
    else -> "Something went wrong. Try again."
}

/** Drops shelves whose ids are already on the page, so a continuation cannot repeat an identity. */
fun appending(new: List<Shelf>, old: List<Shelf>): List<Shelf> {
    val seen = old.map { it.id }.toHashSet()
    return old + new.filter { seen.add(it.id) }
}

/** The next part of a list that grows by continuation as the listener scrolls. */
sealed class MoreState {
    /** The list is complete. */
    data object End : MoreState()

    /** More exists and has not been asked for. */
    data class Ready(val token: String) : MoreState()

    /** The part for this token is on its way. */
    data class Loading(val token: String) : MoreState()

    /** Asking with this token failed; it can be asked again. */
    data class Failed(val token: String) : MoreState()

    val tokenOrNull: String?
        get() = when (this) {
            End -> null
            is Ready -> token
            is Loading -> token
            is Failed -> token
        }

    /** The token to ask with now: none while a part is on its way, or at the end. */
    val askable: String?
        get() = when (this) {
            is Ready -> token
            is Failed -> token
            End, is Loading -> null
        }

    companion object {
        fun of(token: String?): MoreState = token?.let { Ready(it) } ?: End
    }
}

/** A list on screen and where its next part stands. */
data class Paged<T>(val content: T, val more: MoreState)

/**
 * One kind of request a screen makes: its content, or the next part of a list.
 *
 * Starting a request cancels the one out, so only the newest is ever answered. The answer arrives in [scope] (the main
 * thread in the app), and never after its request was replaced or cancelled. Whatever is out is cancelled with the
 * scope (a page popped).
 */
class RequestSlot(private val scope: CoroutineScope) {
    private var job: Job? = null

    /** A request is on its way. */
    val isBusy: Boolean get() = job != null

    fun <T> start(fetch: suspend () -> T, answer: (Result<T>) -> Unit): Job {
        job?.cancel()
        lateinit var started: Job
        // Lazily started, so `job` is set before the request can possibly answer (an immediate dispatcher in tests).
        started = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                Result.success(fetch())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
            if (!currentCoroutineContext().isActive || job !== started) return@launch
            job = null
            answer(result)
        }
        job = started
        started.start()
        return started
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}

/**
 * Something the listener switches on or off (saved to the library, subscribed), shown switched at once.
 *
 * One request is out at a time. Taps made while it is out are kept as the newest wish and sent after it, so the
 * service sees the changes in order and the last tap wins. A refused request puts back what the service last
 * confirmed and drops the wishes made after it.
 */
data class OptimisticToggle(
    /** What the service last said. */
    val confirmed: Boolean,
    val phase: Phase = Phase.Settled,
) {
    sealed class Phase {
        data object Settled : Phase()

        /** A request to switch to [value] is out; [then] is a different value wished for since. */
        data class Sending(val value: Boolean, val then: Boolean?) : Phase()
    }

    /** What to show. */
    val isOn: Boolean
        get() = when (phase) {
            Phase.Settled -> confirmed
            is Phase.Sending -> phase.then ?: phase.value
        }

    val isSending: Boolean get() = phase != Phase.Settled

    /** A tap: the new toggle, and the value to send now (null when a request is already out). */
    fun flip(): Pair<OptimisticToggle, Boolean?> = when (phase) {
        Phase.Settled -> copy(phase = Phase.Sending(!confirmed, then = null)) to !confirmed
        is Phase.Sending -> {
            val wish = !(phase.then ?: phase.value)
            copy(phase = Phase.Sending(phase.value, then = if (wish == phase.value) null else wish)) to null
        }
    }

    /** The answer to the request out: the new toggle, and the next value to send if the listener changed their mind. */
    fun answered(succeeded: Boolean): Pair<OptimisticToggle, Boolean?> {
        val sending = phase as? Phase.Sending ?: return this to null
        if (!succeeded) return copy(phase = Phase.Settled) to null
        val then = sending.then
        if (then != null && then != sending.value) {
            return OptimisticToggle(sending.value, Phase.Sending(then, then = null)) to then
        }
        return OptimisticToggle(sending.value, Phase.Settled) to null
    }

    /** A freshly loaded page says what the service holds. While a request is out, its answer still decides. */
    fun reload(value: Boolean): OptimisticToggle = copy(confirmed = value)
}
