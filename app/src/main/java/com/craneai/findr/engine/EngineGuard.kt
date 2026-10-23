package com.craneai.findr.engine

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One native engine and whether it holds a GPU executor. [closeOnce] is idempotent: the native close runs for the first call only,
 * whoever makes it (a release, an upgrade, the self-test's failure path), so a double close cannot reach the driver.
 * Kept generic over [AutoCloseable] so the tests drive it with a fake instead of the real LiteRT-LM engine.
 */
class EngineHandle<E : AutoCloseable>(val engine: E, val gpu: Boolean) {
  private val closed = AtomicBoolean(false)
  val isClosed: Boolean get() = closed.get()

  /** Closes the engine if nothing has yet. True for the call that closed it, false for every later one. The flag is set first: a close that throws is not retried. */
  fun closeOnce(): Boolean {
    if (!closed.compareAndSet(false, true)) return false
    engine.close()
    return true
  }
}

/**
 * Everything that creates, uses or closes a native engine goes through here ([serial]), so that:
 *  - it runs on ONE dedicated thread ([engineDispatcher]): an engine is created, used and closed on the same thread. The 1.0.0 crash
 *    was a close from a Dispatchers.Default worker, a different thread from the one that built the OpenCL context (Qualcomm's
 *    libCB/libGLESv2_adreno released the context from a thread that had none: fault address 0x6d), see
 *    internal/research/adreno-close-crash.md;
 *  - it holds a mutex as well, so a close never overlaps an inference or another close (belt and braces: the thread already
 *    serialises, the mutex keeps that true if a block ever suspends);
 *  - closing is idempotent ([EngineHandle.closeOnce]);
 *  - on Qualcomm/Adreno ([keepGpuEngines]) an engine that holds a GPU executor is never closed on an idle or memory unload: it
 *    lives for the process and process death frees it. The cost: the model's GPU memory stays charged to the app while it is
 *    cached, so the system reclaims the process earlier (harmless on a 12 GB phone; a cached process is the first to be killed).
 */
class EngineGuard<E : AutoCloseable>(
  private val dispatcher: CoroutineDispatcher,
  private val keepGpuEngines: Boolean,
  private val log: (String) -> Unit = {},
) {
  private val mutex = Mutex()
  @Volatile private var owner: Thread? = null

  /** The engine in use. Read and written only inside [serial]. */
  var current: EngineHandle<E>? = null
    private set

  /** Adreno GPU engines taken out of service but never closed. Strong references: nothing may finalise them on some other thread. */
  private val parked = ArrayList<EngineHandle<E>>()
  val parkedCount: Int get() = synchronized(parked) { parked.size }

  /** The loaded engine holds a GPU executor that this guard will not close on an unload: rebuilding it would leak it. */
  val pinsGpu: Boolean get() = keepGpuEngines && current?.gpu == true

  /** Runs [block] on the engine thread with the lock held. Every call into the engine, and [install]/[unload]/[discard]/[dispose], belongs inside. */
  suspend fun <T> serial(block: () -> T): T = withContext(dispatcher) {
    mutex.withLock {
      owner = Thread.currentThread()
      try { block() } finally { owner = null }
    }
  }

  /** Makes [handle] the engine in use; one already there goes out first ([discard] rules). */
  fun install(handle: EngineHandle<E>) {
    held()
    current?.let { retire(it, "replaced") }
    current = handle
  }

  /**
   * The idle, polite and memory-pressure unload. True when the engine was let go; false when there was none, or when it holds a GPU
   * executor on Adreno and is kept (the caller leaves its bookkeeping alone).
   */
  fun unload(why: String): Boolean {
    held()
    val h = current ?: return false
    if (keepGpuEngines && h.gpu) {
      log("engine unload skipped ($why): the GPU engine stays loaded on this GPU")
      return false
    }
    current = null
    retire(h, why)
    return true
  }

  /**
   * The engine must go because it is stale or is being rebuilt (a new model file, another level): closed, except a GPU engine on
   * Adreno, which is taken out of service and parked unclosed. The next [install] starts from a clean slot either way.
   */
  fun discard(why: String) {
    held()
    val h = current ?: return
    current = null
    retire(h, why)
  }

  /** An engine that was never installed (the self-test rejected it). Same rules as [discard]. */
  fun dispose(handle: EngineHandle<E>) {
    held()
    retire(handle, "rejected by its self-test")
  }

  private fun retire(h: EngineHandle<E>, why: String) {
    if (h.isClosed) return
    if (keepGpuEngines && h.gpu) {
      synchronized(parked) { if (parked.none { it === h }) parked.add(h) }
      log("GPU engine taken out of service and not closed ($why): process death frees it")
      return
    }
    try { h.closeOnce() } catch (t: Throwable) { log("engine close failed ($why): ${t.javaClass.simpleName}") }
  }

  private fun held() = check(owner === Thread.currentThread()) { "engine lifecycle call outside EngineGuard.serial" }

  companion object {
    /** The one thread engines live on. A daemon: it never holds the process open. */
    fun newEngineDispatcher(): CoroutineDispatcher =
      Executors.newSingleThreadExecutor { r -> Thread(r, "trawl-engine").apply { isDaemon = true } }.asCoroutineDispatcher()
  }
}

/** Is this phone's GPU a Qualcomm Adreno? Decided from the device's own identification; no GL or EGL call, no driver is touched. */
object GpuVendor {
  /**
   * [renderer] is GLES20.glGetString(GL_RENDERER) when a caller has one (needs a current GL context; null otherwise).
   * Every Qualcomm Snapdragon SoC carries an Adreno GPU, so the SoC maker ("QTI" from API 31) or the hardware name ("qcom") decide too.
   */
  fun isAdreno(renderer: String?, socManufacturer: String?, hardware: String?): Boolean =
    renderer?.contains("adreno", ignoreCase = true) == true ||
      socManufacturer?.trim().let { it.equals("QTI", ignoreCase = true) || it.equals("Qualcomm", ignoreCase = true) } ||
      hardware?.trim().equals("qcom", ignoreCase = true)

  /** This phone. */
  val adreno: Boolean by lazy {
    isAdreno(
      renderer = null,
      socManufacturer = if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MANUFACTURER else null,
      hardware = android.os.Build.HARDWARE,
    )
  }
}
