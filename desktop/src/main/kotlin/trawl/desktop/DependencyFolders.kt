package trawl.desktop

import java.nio.file.Files
import java.nio.file.Path

/**
 * Folders that hold what a build tool downloaded or made, never what a person keeps: dependency trees, compiler output, caches. They are
 * the busiest folders on a developer's disk (an `npm install` writes thousands of files) and hold tens of thousands of `.ts` and `.js`
 * files that are not the person's documents. The crawl and the watcher do not enter them, the way they do not enter hidden folders, and
 * they are not counted as "unreadable": nothing was refused, they are left out on purpose.
 *
 * Why each rule is safe (a person's photos, scans and documents are not saved into any of these):
 *  - `node_modules`, `bower_components`: written only by npm, yarn, pnpm and bower. Skipped wherever they are, by name.
 *  - `__pycache__`: written only by the Python interpreter. Skipped by name.
 *  - `DerivedData`: Xcode's build products and indexes. Skipped by name.
 *  - `.gradle`: Gradle's cache. A hidden folder, so it was never read ([Crawler.isHidden]); named here so the list reads whole.
 *  - `venv` / `.venv`: skipped only when the folder holds a `pyvenv.cfg`, the file every Python virtual environment starts with. A folder
 *    someone merely named "venv" (a holiday in Venice) has none.
 *  - `Pods`: CocoaPods' checkout; skipped only when a `Podfile` sits beside it.
 *  - `target`: skipped only when a `pom.xml` (Maven) or `Cargo.toml` (Rust) sits beside it; a folder called "target" anywhere else is read.
 *  - `build`: skipped only when a `build.gradle`, `build.gradle.kts` or `package.json` sits beside it; "build" in the middle of a photo library is read.
 */
object DependencyFolders {
  /** Skipped wherever they are. */
  private val BY_NAME = setOf("node_modules", "bower_components", "__pycache__", "DerivedData", ".gradle")

  /** Skipped only when one of these files sits beside the folder. */
  private val BESIDE = mapOf(
    "Pods" to listOf("Podfile"),
    "target" to listOf("pom.xml", "Cargo.toml"),
    "build" to listOf("build.gradle", "build.gradle.kts", "package.json"),
  )

  /** Skipped only when one of these files sits inside the folder. */
  private val INSIDE = mapOf("venv" to listOf("pyvenv.cfg"), ".venv" to listOf("pyvenv.cfg"))

  /** The folder names that may be skipped: a cheap test before any file system call. */
  private val NAMES: Set<String> = BY_NAME + BESIDE.keys + INSIDE.keys

  /** Whether the folder [dir] is a dependency, build or cache folder that is not read. Looks at its name first and at the disk only for the few names that need it. */
  fun isSkipped(dir: Path): Boolean {
    val name = dir.fileName?.toString() ?: return false
    if (name !in NAMES) return false
    if (name in BY_NAME) return true
    BESIDE[name]?.let { markers -> val parent = dir.parent ?: return false; return markers.any { Files.exists(parent.resolve(it)) } }
    INSIDE[name]?.let { markers -> return markers.any { Files.exists(dir.resolve(it)) } }
    return false
  }

  /** True when [dir] is a skipped folder below [root], or sits anywhere inside one. [root] itself is never skipped (the person chose it). */
  fun insideSkipped(root: Path, dir: Path): Boolean {
    if (!dir.startsWith(root) || dir == root) return false
    var p: Path = root
    for (seg in root.relativize(dir)) { p = p.resolve(seg); if (isSkipped(p)) return true }
    return false
  }
}
