package trawl.desktop

import java.io.File
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Turning text a person typed (the name field of a folder chooser, an old entry in a settings file) into a [Path] without a crash.
 * `File.toPath()` throws [InvalidPathException] for text the operating system cannot hold as a path ("C:\x:y" on Windows: a colon past
 * the drive letter; a NUL anywhere); such text means "not a folder", never "stop the app".
 */
object SafePath {
  /** [file] as an absolute, normalised path, or null when the system cannot read it as one. */
  fun of(file: File): Path? = try {
    file.toPath().toAbsolutePath().normalize()
  } catch (_: InvalidPathException) {
    null
  } catch (_: java.io.IOError) {
    null   // toAbsolutePath can fail asking for the working folder
  }

  fun of(text: String): Path? = of(File(text))
}
