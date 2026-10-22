package com.craneai.findr.harness

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Entry point of the usage-sim grid replay (see [UsageSimGrid]). Does nothing unless FINDR_GRID_WORLD is set.
 *   FINDR_GRID_WORLD=<world dir> FINDR_OUT=<output dir> FINDR_GRID_PASS=texts|run
 * The word list is read from FINDR_WORDS (default src/main/assets/english_words.txt.gz, relative to the app module).
 */
class UsageSimGridTest {
  @Test fun replay() {
    val world = System.getenv("FINDR_GRID_WORLD")
    assumeTrue("FINDR_GRID_WORLD not set", world != null)
    val out = File(System.getenv("FINDR_OUT") ?: error("FINDR_OUT not set"))
    val pass = System.getenv("FINDR_GRID_PASS") ?: "run"
    require(pass == "texts" || pass == "run") { "FINDR_GRID_PASS must be texts or run" }
    UsageSimGrid.run(File(world!!), out, pass, File(System.getenv("FINDR_WORDS") ?: "src/main/assets/english_words.txt.gz"))
  }
}
