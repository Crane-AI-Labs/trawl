package trawl.desktop

import java.nio.file.Files
import java.nio.file.Path

/**
 * Whether the system lets Trawl look inside the folders the person chose. On macOS a folder such as Documents or Downloads asks
 * the person for permission the first time; if they said no, the folder is still there but listing it fails ("Operation not
 * permitted"), and a walk of it would find nothing. That is "no permission", and it is told apart from a folder that is simply
 * not there (an unplugged drive), which is not a permission problem and changes nothing.
 */
object FolderAccess {
  /** True when [dir] can be listed. */
  fun canList(dir: Path): Boolean = try {
    Files.newDirectoryStream(dir).use { it.iterator().hasNext() }
    true
  } catch (_: java.io.IOException) {
    false
  } catch (_: SecurityException) {
    false
  }

  /**
   * True when there are chosen folders that exist and not one of them can be listed. One readable folder is enough to keep reading
   * (the others are the person's choice to deal with); folders that are not there are left out.
   */
  fun denied(roots: List<Path>, listable: (Path) -> Boolean = ::canList): Boolean {
    val there = roots.filter { Files.isDirectory(it) }
    return there.isNotEmpty() && there.none(listable)
  }
}
