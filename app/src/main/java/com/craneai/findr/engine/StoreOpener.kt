package com.craneai.findr.engine

import com.craneai.findr.core.IndexOpenProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Thrown by [FindrEngine.store] while the index is opening or cannot be opened; background work that meets it is skipped and redone later. */
class StoreNotReadyException : IllegalStateException("the index is not open")

/**
 * Opens the index off the main thread and keeps trying until it opens (the "engine open" follow-up to the index-wipe fix:
 * a busy or unreadable index used to freeze the app on its main thread and then crash it on every launch).
 *
 * The states: [State.Opening] (the normal "getting ready"), [State.Ready] (holding the store), and [State.Failed] (a reason class and
 * the number of failed opens so far; the loop is waiting to try again). A failed open never throws out of here and never touches the
 * file; the loop waits [firstBackoffMs] doubling to [maxBackoffMs] and tries again, or sooner on [retryNow]. [open] is the blocking
 * open (it may itself retry; it calls its argument at each transient failure, so Failed shows at once rather than after a minute).
 *
 * [prepare] runs on the open's thread before the store is published (rules applied, so nobody sees a store that is not set up); an
 * error from it counts as a failed open. [onReady] runs after publishing, with `recovered = true` when this store followed a failure
 * (the engine then does the crawl it missed).
 *
 * Generic in the store type so the state machine is tested without a database.
 */
class StoreOpener<T : Any>(
  private val scope: CoroutineScope,
  private val ioDispatcher: CoroutineDispatcher,
  private val open: (onTransient: (Throwable) -> Unit) -> T,
  private val classify: (Throwable) -> IndexOpenProblem,
  private val prepare: (T) -> Unit = {},
  private val onReady: suspend (store: T, recovered: Boolean) -> Unit = { _, _ -> },
  private val onFailed: (IndexOpenProblem, Int) -> Unit = { _, _ -> },
  private val close: (T) -> Unit = {},
  private val log: (String) -> Unit = {},
  private val firstBackoffMs: Long = FIRST_BACKOFF_MS,
  private val maxBackoffMs: Long = MAX_BACKOFF_MS,
) {
  sealed interface State {
    data object Opening : State
    class Ready<T>(val store: T) : State
    /** [problem] is why; [failures] counts failed opens in this run (0: a first transient failure, the open itself is still trying). */
    data class Failed(val problem: IndexOpenProblem, val failures: Int) : State
  }

  private val _state = MutableStateFlow<State>(State.Opening)
  val state: StateFlow<State> get() = _state

  private val lock = Any()
  private var job: Job? = null
  private var kicks = Channel<Unit>(Channel.CONFLATED)

  /** The store, or null while it is opening or failing. Never blocks. */
  @Suppress("UNCHECKED_CAST")
  val current: T? get() = (_state.value as? State.Ready<*>)?.store as T?

  /** The index cannot be opened right now (the loop is retrying). */
  val failing: Boolean get() = _state.value is State.Failed

  /** Starts the open loop (once; a second call while running does nothing). */
  fun start() {
    synchronized(lock) {
      if (job?.isActive == true) return
      kicks = Channel(Channel.CONFLATED)
      _state.value = State.Opening
      job = scope.launch { run() }
    }
  }

  /**
   * Waits out [State.Opening]; returns the store once ready, or null at once while the open is failing (the caller skips the work
   * and comes back; the engine re-runs what it missed on recovery).
   */
  suspend fun await(): T? {
    val s = _state.first { it !is State.Opening }
    @Suppress("UNCHECKED_CAST")
    return (s as? State.Ready<*>)?.store as T?
  }

  /** Try again now rather than at the end of the wait. Counts only while failed; a kick sent while an open is in flight is dropped. */
  fun retryNow() {
    synchronized(lock) { if (_state.value is State.Failed) kicks.trySend(Unit) }
  }

  /**
   * Stops the loop and hands back the open store (or null if there was none), leaving the state at [State.Opening] with no loop
   * running, so a caller can close and replace the file and then call [start]. Waits for an open in flight to finish.
   */
  suspend fun detach(): T? {
    val j = synchronized(lock) { job.also { job = null } }
    j?.cancel()
    j?.join()
    val had = current
    _state.value = State.Opening
    return had
  }

  private suspend fun run() {
    var failures = 0
    var backoff = firstBackoffMs
    var lastProblem: IndexOpenProblem? = null
    while (true) {
      try {
        // Not cancellable: a store that was opened must reach [detach] or be closed, never be dropped (an open is bounded, about a minute at worst).
        val store = withContext(ioDispatcher + NonCancellable) {
          val s = open { t ->   // a transient failure inside the open: say so now
            val p = classify(t)
            lastProblem = p
            _state.value = State.Failed(p, failures)
            onFailed(p, failures)
          }
          try { prepare(s) } catch (t: Throwable) { runCatching { close(s) }; throw t }
          s
        }
        val recovered = failures > 0 || lastProblem != null
        if (recovered) log("index opened after $failures failed open(s)")
        _state.value = State.Ready(store)
        try { onReady(store, recovered) } catch (c: CancellationException) { throw c } catch (t: Throwable) {
          log("work after the index opened failed (${t.javaClass.simpleName}); the index itself is open")
        }
        return
      } catch (c: CancellationException) {
        throw c
      } catch (t: Throwable) {
        failures++
        val p = classify(t)
        lastProblem = p
        _state.value = State.Failed(p, failures)
        onFailed(p, failures)
        log("index open failed ($p), attempt $failures; next try in ${backoff / 1000} s")
      }
      kicks.tryReceive()   // a kick that arrived while the open was running is stale
      withTimeoutOrNull(backoff) { kicks.receive() }
      backoff = (backoff * 2).coerceAtMost(maxBackoffMs)
    }
  }

  companion object {
    const val FIRST_BACKOFF_MS = 5_000L
    const val MAX_BACKOFF_MS = 60_000L
  }
}
