package com.craneai.findr.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The rule behind the cached-CPU fix: reading and understanding files (a crawl, the words pass, the embedding loop, quick look,
 * privacy scores) runs only inside a worker (IndexWorker, ChangeWorker), which holds a foreground service or a running job, or in the
 * app while a screen is on show. Anything else in app scope runs in a process Android may rate "cached", and Android kills such a
 * process for using more than a few percent of the CPU (exit reason 9 / subreason 7, seen on the S24: 14 of 16 deaths in a day).
 *
 * A source scan, on purpose: a new caller of these entry points has to be added here by someone who has read this comment.
 */
class NoIndexingOutsideAWorkerTest {
  private val root = File("src/main/java/com/craneai/findr")

  private fun sources(): List<File> = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

  /** Code lines only: comments and doc text are not calls. */
  private fun codeLines(f: File): List<Pair<Int, String>> = f.readLines().mapIndexed { i, l -> i + 1 to l }
    .filter { (_, l) -> l.trimStart().let { !it.startsWith("*") && !it.startsWith("//") && !it.startsWith("/*") } }
    .map { (n, l) -> n to l.substringBefore("//") }

  private fun callers(pattern: Regex): Map<String, List<Int>> = sources().associate { f ->
    f.name to codeLines(f).filter { (_, l) -> pattern.containsMatchIn(l) }.map { it.first }
  }.filterValues { it.isNotEmpty() }

  @Test fun theSourceTreeIsFound() {
    assertTrue("run from the app module: ${root.absolutePath}", root.isDirectory && sources().size > 20)
  }

  @Test fun onlyWorkersAndTheForegroundRefreshCallTheIndexingEntryPoints() {
    // Call sites of the work itself. FileProcessor / the passes are constructed only by the code that is about to run them.
    // videoWords: the catch-up of videos an earlier build read (VideoCatchUp) reads frames and runs OCR, so it is heavy work too.
    val entry = Regex("""(\bwordsPass\(|\bFileProcessor\(|\bQuickLookPass\(|\bPrivacyPass\(|\bcrawl\(|\bunderstand\(|\bvideoWords\(|\bcatchUpOldVideo\()""")
    val allowed = setOf(
      "IndexWorker.kt", "ChangeWorker.kt",                         // the workers
      "FindrEngine.kt",                                            // the in-app refresh (UI visible only), checked below
      "FileProcessor.kt", "QuickLookPass.kt", "PrivacyPass.kt",    // the definitions
    )
    val found = callers(entry).keys
    assertTrue("indexing entry points are called from ${found - allowed}: run it inside a worker (a foreground service or a job), see RunHandoff.unprotected", allowed.containsAll(found))
  }

  @Test fun theInAppWordsPassStopsWhenTheScreenIsGone() {
    val lines = codeLines(File(root, "engine/FindrEngine.kt")).filter { (_, l) -> Regex("""\bwordsPass\(""").containsMatchIn(l) && !l.contains("fun wordsPass") }
    assertEquals("one in-app caller of the words pass: $lines", 1, lines.size)
    assertTrue("it must stop when no screen is on show: ${lines[0].second}", lines[0].second.contains("!uiVisible"))
  }

  @Test fun appScopeCrawlsAreTheKnownFour() {
    // 1 the in-app refresh (screen on show), 2 the catch-up after an index that failed to open (guarded by RunHandoff.unprotected),
    // 3 the person tapping Start, 4 the person changing where to look. A fifth is a new way to read files outside a worker.
    val crawls = codeLines(File(root, "engine/FindrEngine.kt")).filter { (_, l) -> Regex("""\bcrawl\(forceFull""").containsMatchIn(l) && !l.contains("fun crawl") }
    assertEquals("crawls in app scope: $crawls", 4, crawls.size)
    assertTrue("the catch-up crawl asks RunHandoff.unprotected first: $crawls", crawls.any { (_, l) -> l.contains("RunHandoff.unprotected") })
  }

  @Test fun everyHeavyLoopOfTheIndexWorkerStopsOnTheProtectionRule() {
    val text = codeLines(File(root, "engine/IndexWorker.kt")).joinToString("\n") { it.second }
    // The stop lambdas handed to the passes and the main loop go through mustStop() (which folds in the protection rule), never bare isStopped.
    for (needle in listOf("wordsPass(processor, stop = { mustStop()", "QuickLookPass(applicationContext, engine) { mustStop() }", "PrivacyPass(applicationContext, engine) { mustStop() }", "while (!mustStop())")) {
      assertTrue("IndexWorker lost: $needle", text.contains(needle))
    }
    assertTrue("no pass may be handed a bare isStopped", !Regex("""(QuickLookPass|PrivacyPass)\(applicationContext, engine\) \{ isStopped \}""").containsMatchIn(text))
    // The catch-up of old videos (VideoCatchUp) is one video per turn of the bulk loop, so the loop's stop, polite, heat and charger checks run between videos.
    val catchUps = Regex("""\bcatchUpOldVideo\(engine, processor""").findAll(text).map { it.range.first }.toList()
    assertEquals("one call of the old-video catch-up: $catchUps", 1, catchUps.size)
    assertTrue("it runs inside the bulk loop", text.indexOf("while (!mustStop())") in 0 until catchUps.single())
    assertTrue("no words pass may stop on a bare isStopped", !Regex("""wordsPass\(processor, stop = \{ isStopped""").containsMatchIn(text))
  }
}
