package trawl.desktop

import java.nio.file.Path

/**
 * The kind of place a folder is in, for telling the person where the system said no. Only these fixed words ever reach the log, the
 * window or a report: never the name of a folder.
 */
enum class Place(val key: String) {
  DESKTOP("place_desktop"), DOCUMENTS("place_documents"), DOWNLOADS("place_downloads"), PICTURES("place_pictures"),
  ICLOUD("place_icloud"), REMOVABLE("place_removable"), OTHER("place_other");

  companion object {
    private val HOME_FOLDERS = mapOf("desktop" to DESKTOP, "documents" to DOCUMENTS, "downloads" to DOWNLOADS, "pictures" to PICTURES)

    /** The kind of place [path] is in: one of the home folders macOS asks about, iCloud Drive, a drive plugged in (/Volumes), or something else. */
    fun of(path: Path, home: Path): Place {
      val p = path.toAbsolutePath().normalize()
      val h = home.toAbsolutePath().normalize()
      if (p.startsWith(h)) {
        val rel = h.relativize(p)
        val first = rel.firstOrNull()?.toString()?.lowercase() ?: return OTHER
        if (first == "library" && rel.nameCount >= 2 && rel.getName(1).toString().equals("Mobile Documents", true)) return ICLOUD
        return HOME_FOLDERS[first] ?: OTHER
      }
      val first = p.firstOrNull()?.toString()
      if (first.equals("Volumes", true) || first.equals("media", true) || first.equals("mnt", true)) return REMOVABLE
      return OTHER
    }
  }
}

/** The words for a [BlockedSummary], worked out apart from the drawing so Settings, the detail panel and a test read the same lines. */
object BlockedNotice {
  /** Up to three lines: what the system closed (and where), how to open it, and what failed some other way. Empty when everything could be read. */
  fun lines(b: BlockedSummary, os: Os = Platform.os): List<String> {
    val out = ArrayList<String>()
    if (b.system > 0) {
      val where = b.where.keys.sorted().joinToString(", ") { t(it.key) }
      out += tn("blocked_system", b.system, b.system, where)
      out += t(if (os == Os.MAC) "blocked_fix_mac" else "blocked_fix")
    }
    if (b.other > 0) out += tn("blocked_other", b.other, b.other)
    return out
  }
}

/**
 * The folders the last walks could not look inside, as counts only. [system] is the ones the system said no to (on a Mac that is its
 * privacy setting, Files & Folders or Full Disk Access; elsewhere the folder's own permissions); [other] failed some other way (a drive
 * that went away, a share that dropped). [where] says which kind of place the [system] ones are in.
 */
data class BlockedSummary(val system: Int = 0, val other: Int = 0, val where: Map<Place, Int> = emptyMap()) {
  val total: Int get() = system + other

  companion object {
    val NONE = BlockedSummary()

    fun of(blocked: Map<Path, Block>, home: Path): BlockedSummary {
      if (blocked.isEmpty()) return NONE
      val where = java.util.EnumMap<Place, Int>(Place::class.java)
      var system = 0; var other = 0
      for ((path, why) in blocked) {
        if (why == Block.DENIED) { system++; where.merge(Place.of(path, home), 1, Int::plus) } else other++
      }
      return BlockedSummary(system, other, where.toMap())
    }
  }

  /** The line for trawl.log: counts and kinds of place, no names. */
  fun logLine(): String =
    if (total == 0) "every folder can be read" else
      "$total folders unreadable (system said no: $system${if (where.isEmpty()) "" else " in " + where.entries.sortedBy { it.key }.joinToString(", ") { "${it.key.name.lowercase()} ${it.value}" }}; other: $other); nothing under them is deleted"
}
