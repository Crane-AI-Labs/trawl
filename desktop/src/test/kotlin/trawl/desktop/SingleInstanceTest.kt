package trawl.desktop

import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** One Trawl per data folder: the first holds the lock and listens, a second tells it to show itself and exits. */
class SingleInstanceTest {
  private val dirs = ArrayList<File>()
  private val open = ArrayList<SingleInstance>()

  private fun data(): File = Files.createTempDirectory("trawl-one").toFile().also { dirs += it }

  private fun first(dir: File): SingleInstance =
    assertIs<SingleInstance.Outcome.First>(SingleInstance.acquire(dir, retries = 1, waitMs = 10)).instance.also { open += it }

  @AfterTest fun clean() { open.forEach { it.close() }; dirs.forEach { it.deleteRecursively() } }

  @Test fun theFirstLaunchTakesTheLockAndASecondOneIsForwarded() {
    val dir = data()
    val one = first(dir)
    val got = AtomicReference<String>()
    val latch = CountDownLatch(1)
    one.listen { got.set(it); latch.countDown() }
    assertIs<SingleInstance.Outcome.Forwarded>(SingleInstance.acquire(dir, command = "show"))
    assertTrue(latch.await(3, TimeUnit.SECONDS), "the first Trawl heard it")
    assertEquals("show", got.get())
  }

  @Test fun theQuickCommandArrivesToo() {
    val dir = data()
    val one = first(dir)
    val got = AtomicReference<String>()
    val latch = CountDownLatch(1)
    one.listen { got.set(it); latch.countDown() }
    assertIs<SingleInstance.Outcome.Forwarded>(SingleInstance.acquire(dir, command = "quick"))
    assertTrue(latch.await(3, TimeUnit.SECONDS))
    assertEquals("quick", got.get())
  }

  @Test fun aWordThatIsNotACommandIsAnsweredButNotObeyed() {
    val dir = data()
    val one = first(dir)
    val heard = ArrayList<String>()
    one.listen { synchronized(heard) { heard += it } }
    // The caller is let go (it got an answer) but the unknown word reaches no handler.
    assertIs<SingleInstance.Outcome.Forwarded>(SingleInstance.acquire(dir, command = "format-disk"))
    Thread.sleep(200)
    synchronized(heard) { assertTrue(heard.isEmpty(), "heard $heard") }
  }

  @Test fun theLockIsFreeAgainWhenTheFirstOneCloses() {
    val dir = data()
    val one = first(dir)
    one.close()
    open.remove(one)
    val again = first(dir)
    assertTrue(File(dir, "instance.lock").readText().trim().toLongOrNull() == ProcessHandle.current().pid(), "the lock file names the process")
    again.close()
  }

  @Test fun aLeftoverSocketFromACrashIsReplaced() {
    val dir = data()
    File(dir, "instance.sock").writeText("left behind")
    val one = first(dir)
    val latch = CountDownLatch(1)
    one.listen { latch.countDown() }
    assertIs<SingleInstance.Outcome.Forwarded>(SingleInstance.acquire(dir))
    assertTrue(latch.await(3, TimeUnit.SECONDS))
  }

  @Test fun aFirstTrawlThatDoesNotAnswerIsNotReplacedByASecondIndex() {
    val dir = data()
    first(dir)   // holds the lock, never calls listen: nobody answers
    val start = System.nanoTime()
    assertIs<SingleInstance.Outcome.Unreachable>(SingleInstance.acquire(dir, retries = 2, waitMs = 20))
    assertTrue((System.nanoTime() - start) / 1_000_000 < 10_000, "gives up in a few seconds, not never")
  }

  @Test fun theStartUpCallEndsTheProcessOnlyForASecondLaunch() {
    Strings.use(Locale.ENGLISH)
    val dir = data()
    val one = SingleInstance.firstOrExit(dir) { error("the first launch must not exit") }
    open += one
    one.listen { }
    class Exited(val code: Int) : RuntimeException()
    val e = assertFailsWith<Exited> { SingleInstance.firstOrExit(dir, exit = { throw Exited(it) }) }
    assertEquals(0, e.code, "a second launch that reached the first exits quietly")
  }

  @Test fun theSocketPathStaysShortEnoughForTheOperatingSystem() {
    val short = SingleInstance.socketFor(File("/tmp/t"))
    assertEquals(File("/tmp/t/instance.sock"), short)
    val long = SingleInstance.socketFor(File("/tmp/" + "x".repeat(150)))
    assertTrue(long.absolutePath.toByteArray().size < 104, long.absolutePath)
    assertEquals(long, SingleInstance.socketFor(File("/tmp/" + "x".repeat(150))), "the same folder gets the same path")
  }
}
