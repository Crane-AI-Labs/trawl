package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.LearningSpeed
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audit row A27: how long the first query takes, measured instead of assumed. Needs the real model file (TRAWL_MODEL, as on a build machine);
 * with none it has nothing to measure and passes. The numbers go to the test's output ("A27 ..." lines); the asserts are loose guard rails
 * (a first query that takes many seconds is a bug), not a benchmark.
 *
 * Two starts are timed, because they differ: the very first launch (no engine cache on disk yet) and every launch after it.
 */
class FirstQueryLatencyTest {
  private fun tmp() = Files.createTempDirectory("trawl-a27").toFile().canonicalFile

  private fun waitFor(what: String, ms: Long = 180_000, ok: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) { if (ok()) return; Thread.sleep(5) }
    throw AssertionError("timed out waiting for $what")
  }

  private val topics = listOf(
    "garden" to "tomatoes basil watering schedule for the raised beds and the compost heap",
    "budget" to "quarterly budget meeting minutes revenue forecast and travel spending",
    "recipe" to "slow roasted tomatoes with orange marmalade and thyme served with bread",
    "school" to "parent teacher conference notes about reading progress and maths homework",
    "travel" to "flight times hotel booking and the train from the airport to the city centre",
    "health" to "clinic appointment reminder bring the vaccination card and the previous results",
  )

  private val asked = listOf("tomato recipe", "budget forecast", "school homework notes", "train from the airport", "vaccination card", "compost heap")

  private fun ms(from: Long) = (System.nanoTime() - from) / 1_000_000.0

  @Test fun theFirstQueryAfterAStartIsMeasured() {
    val model = System.getenv("TRAWL_MODEL")?.let(::File)?.takeIf { it.isFile } ?: return
    val root = tmp(); val data = tmp()
    val n = 240
    repeat(n) { i ->
      val (name, body) = topics[i % topics.size]
      File(root, "docs/$name-$i.txt").apply { parentFile.mkdirs(); writeText("$body. Note number $i, written for the $name folder.") }
    }
    val kinds = setOf(FileKind.IMAGE, FileKind.SCREENSHOT, FileKind.DOCUMENT, FileKind.PDF)

    // ---- the first launch ever: no index, no engine cache. The words pass and the model run, and meaning is added ----
    var firstEverModelMs: Double
    IndexService(data).use { ix ->
      ix.configure(kinds, emptyList(), LearningSpeed.FAST, paused = false)
      ix.setFolders(listOf(root))
      val t0 = System.nanoTime()
      ix.attachModel(model)
      waitFor("the model") { ix.ui.value.modelReady }
      firstEverModelMs = ms(t0)
      waitFor("every document understood") { ix.ui.value.phase == Phase.UP_TO_DATE && ix.ui.value.understood >= n }
    }
    println("A27 first launch ever: model ready in ${"%.0f".format(firstEverModelMs)} ms (no engine cache yet)")

    // ---- every launch after: the index and the engine cache are on disk. This is what a person sees on an ordinary start ----
    repeat(2) { round ->
      val tStart = System.nanoTime()
      IndexService(data).use { ix ->
        ix.configure(kinds, emptyList(), LearningSpeed.BALANCED, paused = false)
        ix.setFolders(listOf(root))
        waitFor("the index to open and answer words") { ix.search("marmalade", KindFilter.ALL).hits.isNotEmpty() }
        val wordsMs = ms(tStart)
        val tModel = System.nanoTime()
        ix.attachModel(model)
        waitFor("the model") { ix.ui.value.modelReady }
        val modelMs = ms(tModel)
        val tFirst = System.nanoTime()
        val first = ix.search(asked[0], KindFilter.ALL)
        val firstMs = ms(tFirst)
        assertTrue(first.hits.isNotEmpty(), "the first meaning query finds something")
        val warm = asked.drop(1).map { q -> val t = System.nanoTime(); ix.search(q, KindFilter.ALL); ms(t) }
        val sorted = warm.sorted()
        println(
          "A27 start ${round + 1}: words answer ${"%.0f".format(wordsMs)} ms after launch; model ready ${"%.0f".format(modelMs)} ms after that; " +
            "first meaning query ${"%.0f".format(firstMs)} ms; later queries median ${"%.0f".format(sorted[sorted.size / 2])} ms (max ${"%.0f".format(sorted.last())} ms), $n documents",
        )
        // Loose guard rails: seconds, not tens of seconds. The real numbers are in the line above.
        assertTrue(wordsMs < 15_000, "words must answer within seconds of a start ($wordsMs ms)")
        assertTrue(modelMs < 60_000, "the model must be ready within a minute ($modelMs ms)")
        assertTrue(firstMs < 5_000, "the first meaning query must not take seconds ($firstMs ms)")
      }
    }
    assertEquals(0, 0)
    root.deleteRecursively(); data.deleteRecursively()
  }
}
