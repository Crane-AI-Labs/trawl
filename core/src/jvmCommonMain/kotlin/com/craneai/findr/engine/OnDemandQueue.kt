package com.craneai.findr.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Work asked for by what is on screen (the privacy score of a thumbnail that is shown blurred until it is scored;
 * internal/research/blur-until-scored.md), done in the background with these manners:
 *  - **one job at a time**: a single worker coroutine in [scope] (the privacy pass's own thread), so showing a page of new photos
 *    never starts a page of model runs;
 *  - **the newest ask first**: the photos the user is looking at now, not the ones scrolled past;
 *  - **dropped when nobody waits**: an ask whose callers were all cancelled (their thumbnails left the screen) is removed before it
 *    starts; one already running finishes (its result is still worth saving) but nobody waits for it;
 *  - **shared**: two callers asking for the same key (a grid tile and a row of the same photo) share one job;
 *  - [hold] runs before each job (the engine waits there while the user scrolls), and the newest ask is picked after it;
 *  - [offer] hands in a result found another way (the background pass), so its callers stop waiting.
 * [work] should not throw; if it does, its callers get the exception.
 */
class OnDemandQueue<K : Any, V>(
  private val scope: CoroutineScope,
  private val hold: suspend () -> Unit = {},
  private val work: suspend (K) -> V,
) {
  private class Ask<V> {
    val result = CompletableDeferred<V>()
    var waiters = 0
  }

  private val lock = Any()
  private val asks = LinkedHashMap<K, Ask<V>>()   // insertion order: the newest ask is last
  private var current: Pair<K, Ask<V>>? = null
  private val wake = Channel<Unit>(Channel.CONFLATED)
  private var worker: Job? = null

  /** Jobs started so far. */
  @Volatile var started = 0
    private set

  /** The most jobs that ever ran at the same time (1 by construction; the tests check it). */
  @Volatile var mostAtOnce = 0
    private set
  @Volatile private var running = 0

  /** Asks waiting to start (not counting the one running). */
  val waiting: Int get() = synchronized(lock) { asks.size }

  /** True while a job runs or an ask waits. */
  val busy: Boolean get() = synchronized(lock) { asks.isNotEmpty() || current != null }

  /** The result for [key]: asks for it (or joins the ask already made) and suspends until it is done. Cancelling drops the ask. */
  suspend fun get(key: K): V {
    val ask = synchronized(lock) {
      val inFlight = current?.takeIf { it.first == key }?.second
      val a = inFlight ?: (asks.remove(key) ?: Ask())
      a.waiters++
      if (a !== inFlight) asks[key] = a   // (re)inserted last: the newest ask
      if (worker?.isActive != true) worker = scope.launch { loop() }
      a
    }
    wake.trySend(Unit)
    try {
      return ask.result.await()
    } finally {
      synchronized(lock) {
        ask.waiters--
        if (ask.waiters == 0 && !ask.result.isCompleted && asks[key] === ask) asks.remove(key)
      }
    }
  }

  /** A result found another way: the callers waiting for [key] get it now, and the ask is not started (or no longer awaited). */
  fun offer(key: K, value: V) {
    val ask = synchronized(lock) { asks.remove(key) ?: current?.takeIf { it.first == key }?.second }
    ask?.result?.complete(value)
  }

  private suspend fun loop() {
    while (true) {
      if (synchronized(lock) { asks.isEmpty() }) {
        wake.receive()
        continue
      }
      hold()
      val picked = synchronized(lock) {
        val e = asks.entries.lastOrNull() ?: return@synchronized null
        asks.remove(e.key)
        (e.key to e.value).also { current = it }
      } ?: continue
      started++
      running++
      if (running > mostAtOnce) mostAtOnce = running
      try {
        val v = work(picked.first)
        picked.second.result.complete(v)
      } catch (e: CancellationException) {
        picked.second.result.cancel(e)
        throw e
      } catch (t: Throwable) {
        picked.second.result.completeExceptionally(t)
      } finally {
        running--
        synchronized(lock) { current = null }
      }
    }
  }
}
