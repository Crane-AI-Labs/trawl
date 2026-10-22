package trawl.desktop

import java.io.File
import java.util.concurrent.TimeUnit

/** Picks the text reader for this OS and puts its helper script (shipped inside the app) on disk where it can run. */
object Ocrs {
  /** "windows", "tesseract", "apple" or "none": which reader this computer has. Cheap; Settings shows it. */
  fun kind(os: Os = Platform.os): String = when (os) {
    Os.WINDOWS -> "windows"
    Os.MAC -> "apple"
    Os.LINUX -> if (tesseractPresent()) "tesseract" else "none"
  }

  fun choose(dataDir: File, os: Os = Platform.os): Ocr? = when (kind(os)) {
    "windows" -> WindowsOcr(extract("ocr-windows.ps1", File(dataDir, "helpers")))
    "apple" -> AppleOcr(appleHelper(File(dataDir, "helpers")))
    "tesseract" -> TesseractOcr()
    else -> null
  }

  /**
   * The Vision helper. The build compiles it with swiftc into the app (`ocr-stdin-apple`), so a Mac without Xcode's tools can still
   * read screenshots; only a developer run of a bare jar, which lacks it, compiles the Swift source on first use.
   */
  private fun appleHelper(dir: File): File {
    val ready = Ocrs::class.java.classLoader.getResource("ocr-stdin-apple") != null
    return if (ready) extract("ocr-stdin-apple", dir).also { it.setExecutable(true) } else AppleOcr.build(extract("ocr-stdin.swift", dir), dir)
  }

  private fun tesseractPresent(): Boolean = try {
    val p = ProcessBuilder("tesseract", "--version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0
  } catch (_: Exception) { false }

  /** Copies a helper out of the app's resources (they cannot run from inside a jar). Rewritten when the app's copy is newer or different. */
  private fun extract(name: String, dir: File): File {
    dir.mkdirs()
    val out = File(dir, name)
    val bytes = Ocrs::class.java.classLoader.getResourceAsStream(name)?.use { it.readBytes() } ?: error("helper $name is missing from the app")
    if (!out.isFile || !out.readBytes().contentEquals(bytes)) out.writeBytes(bytes)
    return out
  }
}
