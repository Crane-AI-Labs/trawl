package trawl.desktop.shell

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The macOS Quick Look helper, run the only safe way a test can: its no-window commands. `--self-check` loads QuickLookUI and shows nothing;
 * `show` with no files and no arguments exits with a word. The panel itself is never opened here (that needs a person at the Mac).
 * Skipped anywhere the helper has not been built (every non-Mac machine, and a Mac build made without swiftc).
 */
class QuickLookHelperTest {
  private fun helper(): File {
    assumeTrue(System.getProperty("os.name").orEmpty().lowercase().contains("mac"), "macOS only")
    val f = File("build/native/${QuickLookMac.HELPER}")
    assumeTrue(f.canExecute(), "the helper is not built (run the build on a Mac with swiftc)")
    return f
  }

  private fun run(vararg args: String): String {
    val p = ProcessBuilder(listOf(helper().absolutePath) + args).redirectErrorStream(true).start()
    p.outputStream.close()
    val out = p.inputStream.bufferedReader().readText().trim()
    assumeTrue(p.waitFor(30, TimeUnit.SECONDS), "the helper did not exit")
    return out
  }

  @Test fun selfCheckLoadsTheFrameworkAndOpensNothing() = assertEquals("ok:quicklook", run("--self-check"))

  @Test fun noArgumentsIsAUsageErrorNotAWindow() = assertEquals("error:usage", run())

  @Test fun showOfAFileThatDoesNotExistIsAnErrorNotAWindow() = assertEquals("error:nofiles", run("show", "0", "/nonexistent/trawl-quicklook-test"))
}
