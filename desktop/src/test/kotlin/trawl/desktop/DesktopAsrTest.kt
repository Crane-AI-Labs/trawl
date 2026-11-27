package trawl.desktop

import com.craneai.findr.core.AudioTranscript
import com.craneai.findr.core.SpeechSegment
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Verifies [DesktopAsr] idle native-memory reclaim, thread-safe locking, and transparent lazy reload on the next call. */
class DesktopAsrTest {
  private class FakeDecoder : MediaDecoder {
    override val name = "fake"
    override fun probe(file: File) = MediaInfo(1_000, hasVideo = false, hasAudio = true)
    override fun frame(file: File, timeMs: Long, maxSide: Int): ByteArray? = null
    override fun audio(file: File, startMs: Long, spanMs: Long, sink: (ByteArray, Int) -> Unit) = Unit
    override fun close() = Unit
  }

  private class FakeRuntime(
    private val onTranscribe: () -> Unit = {},
  ) : AsrRuntime {
    var loaded = false
    val loadCount = AtomicInteger()
    val releaseCount = AtomicInteger()
    val transcribeCount = AtomicInteger()

    override fun installed(): Boolean = true
    override fun available(): Boolean = true
    override fun isLoaded(): Boolean = loaded

    override fun transcribe(numThreads: Int, decoder: MediaDecoder, file: File, startMs: Long, spanMs: Long, stop: () -> Boolean): AudioTranscript {
      if (!loaded) {
        loaded = true
        loadCount.incrementAndGet()
      }
      onTranscribe()
      val n = transcribeCount.incrementAndGet()
      return AudioTranscript(listOf(SpeechSegment(startMs, startMs + 1_000, "clip $n")), emptyList(), 1.0)
    }

    override fun release(): Boolean {
      if (!loaded) return false
      loaded = false
      releaseCount.incrementAndGet()
      return true
    }
  }

  private fun waitUntil(timeoutMs: Long = 2_000L, predicate: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (System.nanoTime() < deadline) {
      if (predicate()) return true
      Thread.sleep(10)
    }
    return predicate()
  }

  @Test fun idleTimeoutReleasesHandlesAndNextTranscribeReloadsSeamlessly() {
    val rt = FakeRuntime()
    val callbacks = AtomicInteger()
    val asr = DesktopAsr(rt, idleReleaseMs = 60L, onReleased = { callbacks.incrementAndGet() })
    try {
      assertFalse(asr.isLoaded)

      val first = asr.transcribe(FakeDecoder(), File("a.wav"), 0L, 1_000L) { false }
      assertEquals("clip 1", first.speech.single().text)
      assertTrue(asr.isLoaded)
      assertEquals(1, rt.loadCount.get())
      assertEquals(0, rt.releaseCount.get())

      // After idle timeout, handles are automatically released and onReleased fires once.
      assertTrue(waitUntil { !asr.isLoaded }, "expected idle release to unload ASR handles")
      assertEquals(1, rt.releaseCount.get())
      assertEquals(1, callbacks.get())

      // A subsequent audio/video file transparently reloads and transcribes cleanly.
      val second = asr.transcribe(FakeDecoder(), File("b.wav"), 5_000L, 1_000L) { false }
      assertEquals("clip 2", second.speech.single().text)
      assertTrue(asr.isLoaded)
      assertEquals(2, rt.loadCount.get())

      assertTrue(waitUntil { !asr.isLoaded }, "expected second idle release to unload ASR handles again")
      assertEquals(2, rt.releaseCount.get())
      assertEquals(2, callbacks.get())
    } finally {
      asr.close()
    }
  }

  @Test fun explicitReleaseIsIdempotentAndResetsCleanly() {
    val rt = FakeRuntime()
    val callbacks = AtomicInteger()
    val asr = DesktopAsr(rt, idleReleaseMs = 60_000L, onReleased = { callbacks.incrementAndGet() })
    try {
      assertFalse(asr.release(), "releasing before first load is a no-op")
      asr.transcribe(FakeDecoder(), File("a.wav"), 0L, 1_000L) { false }
      assertTrue(asr.isLoaded)
      assertTrue(asr.release(), "explicit release frees loaded handles")
      assertFalse(asr.isLoaded)
      assertFalse(asr.release(), "second release is a no-op")
      assertEquals(1, rt.releaseCount.get())
      assertEquals(1, callbacks.get())

      // Reloads seamlessly after explicit release
      val res = asr.transcribe(FakeDecoder(), File("b.wav"), 0L, 1_000L) { false }
      assertEquals("clip 2", res.speech.single().text)
      assertEquals(2, rt.loadCount.get())
    } finally {
      asr.close()
    }
  }

  @Test fun concurrentReleaseWaitsForInFlightTranscribeUnderLock() {
    val entered = CountDownLatch(1)
    val allowFinish = CountDownLatch(1)
    val rt = FakeRuntime(onTranscribe = {
      entered.countDown()
      assertTrue(allowFinish.await(2, TimeUnit.SECONDS))
    })
    val asr = DesktopAsr(rt, idleReleaseMs = 60_000L)
    try {
      val worker = Thread { asr.transcribe(FakeDecoder(), File("long.wav"), 0L, 5_000L) { false } }.apply { start() }
      assertTrue(entered.await(2, TimeUnit.SECONDS))

      val releaserDone = CountDownLatch(1)
      val releaser = Thread {
        assertTrue(asr.release())
        releaserDone.countDown()
      }.apply { start() }

      // Releaser must wait until transcribe exits the lock; handles remain loaded during transcribe.
      assertFalse(releaserDone.await(50, TimeUnit.MILLISECONDS), "release() must not run while transcribe() is in flight")
      assertTrue(rt.loaded)

      allowFinish.countDown()
      worker.join(2_000)
      releaser.join(2_000)
      assertTrue(releaserDone.await(1, TimeUnit.SECONDS))
      assertFalse(asr.isLoaded)
      assertEquals(1, rt.releaseCount.get())
    } finally {
      asr.close()
    }
  }

  @Test fun transcribeAfterCloseThrowsWithoutReloadingHandles() {
    val rt = FakeRuntime()
    val asr = DesktopAsr(rt, idleReleaseMs = 60_000L)
    asr.transcribe(FakeDecoder(), File("a.wav"), 0L, 1_000L) { false }
    assertEquals(1, rt.loadCount.get())
    asr.close()
    assertFalse(asr.isLoaded)

    var cancelled = false
    try {
      asr.transcribe(FakeDecoder(), File("after-close.wav"), 0L, 1_000L) { false }
    } catch (_: java.util.concurrent.CancellationException) {
      cancelled = true
    }
    assertTrue(cancelled, "transcribe() after close() must throw CancellationException instead of reloading native handles")
    assertFalse(asr.isLoaded, "closed DesktopAsr must never reload native handles")
    assertEquals(1, rt.loadCount.get())
  }
}
