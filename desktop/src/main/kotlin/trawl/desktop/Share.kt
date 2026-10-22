package trawl.desktop

import java.io.File
import trawl.desktop.privacy.HelperRunner

/** One system's own way to hand files to another app: the macOS share sheet, Windows' Share pane. */
interface ShareFacility {
  /** Opens the system's share UI for [files]; false when it could not be shown. */
  fun share(files: List<File>): Boolean
}

/**
 * "Share" (B4). The button and menu entry appear only where [forOs] has a facility, so nothing is faked.
 *
 *  - macOS: `share-apple` (native/share-apple.swift), NSSharingServicePicker, compiled into the app by the build. It is missing from a build
 *    made off a Mac, and then Share is simply not offered.
 *  - Windows: `share-windows.ps1` (native/share-windows.ps1), the Share pane through DataTransferManager, run by Windows PowerShell 5.1.
 *  - Linux: no system share sheet exists, so none is shown. Copy (B3) and Open with cover the need there.
 *
 * Both helpers speak one line: "ok" (a target took the files), "cancelled" (the sheet was dismissed) or "error:<why>". Dismissing is not a
 * failure and says nothing. The privacy leave dialog is asked by the screens before [share] is ever called, for sensitive files.
 */
object Share {
  /** The helper's own limit: how long the sheet may stay open before it gives up (the helper times out at 10 minutes). */
  private const val SHEET_MS = 11 * 60_000L

  fun forOs(
    os: Os,
    dataDir: () -> File = { Platform.dataDir(os) },
    runner: HelperRunner = HelperRunner.Process,
    hasResource: (String) -> Boolean = { Share::class.java.classLoader.getResource(it) != null },
  ): ShareFacility? = when (os) {
    Os.MAC -> if (hasResource(MAC_HELPER)) HelperShare(
      prepare = { Helpers.extract(MAC_HELPER, File(dataDir(), "helpers")).also { it.setExecutable(true) }.absolutePath.let(::listOf) },
      runner = runner, argBudget = Int.MAX_VALUE,
    ) else null
    Os.WINDOWS -> HelperShare(
      prepare = {
        val script = Helpers.extract(WINDOWS_HELPER, File(dataDir(), "helpers")).absolutePath
        listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script)
      },
      runner = runner, argBudget = WINDOWS_ARG_BUDGET,
    )
    Os.LINUX -> null    // no system share sheet exists: never offered
  }

  const val MAC_HELPER = "share-apple"
  const val WINDOWS_HELPER = "share-windows.ps1"
  /** CreateProcess takes 32,767 characters of command line in all; the paths get most of it and the rest is the helper's own words. */
  const val WINDOWS_ARG_BUDGET = 24_000

  internal fun sheetTimeout() = SHEET_MS

  /** The facility in use; a test sets it. */
  @Volatile var facility: ShareFacility? = forOs(Platform.os)

  val available: Boolean get() = facility != null

  fun share(files: List<File>): Boolean = runCatching { facility?.share(files.take(Clip.LIMIT)) == true }.getOrDefault(false)
}

/** What a helper's one line means: a taken share and a dismissed sheet are both fine; anything else is a failure to show it. */
object ShareProtocol {
  fun succeeded(line: String?): Boolean = line == "ok" || line == "cancelled"
}

/**
 * Runs a share helper as `<prepare()> share <path>...` and reads its one line. [prepare] gives the command up to the helper's own arguments (and
 * puts the helper on disk the first time), so nothing is extracted until a person actually shares. Paths beyond [argBudget] characters are
 * left off rather than failing the whole command.
 */
class HelperShare(
  private val prepare: () -> List<String>,
  private val runner: HelperRunner = HelperRunner.Process,
  private val argBudget: Int = Int.MAX_VALUE,
) : ShareFacility {
  override fun share(files: List<File>): Boolean {
    val paths = withinBudget(files.filter { it.isFile }.map { it.absolutePath })
    if (paths.isEmpty()) return false
    return ShareProtocol.succeeded(runner.run(prepare() + "share" + paths, Share.sheetTimeout()))
  }

  private fun withinBudget(paths: List<String>): List<String> {
    var used = 0
    return paths.takeWhile { p -> (used + p.length + 3 <= argBudget).also { if (it) used += p.length + 3 } }
  }
}
