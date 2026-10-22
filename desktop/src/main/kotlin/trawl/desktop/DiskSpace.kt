package trawl.desktop

import java.io.File

/**
 * Warns before the disk gets too full, and holds reading off before it is. The phone has the pause reason (storage low) and its
 * sentence but no rule in code, so this is the desktop's own, kept simple and written down here:
 *
 *  - **LOW** (reading waits): less than 500 MB free. It resumes only above 1 GB, so a disk hovering at the line does not flap.
 *  - **WARN** (a plain message, reading goes on): less than 2 GB free, or the index will not fit. "Will not fit" is the estimate of the
 *    growth still to come ([IndexAdmin.Detail.growthBytes]) plus the 500 MB that must stay free.
 *  - **OK** otherwise.
 *
 * SQLite needs room to write its log and to rebuild after a crash, which is what the 500 MB is for; the index of a very large
 * folder is a few hundred MB, so 2 GB gives a person a day or more to act.
 */
object DiskSpace {
  enum class Level { OK, WARN, LOW }

  const val MB = 1024L * 1024L
  const val LOW_BELOW = 500 * MB
  const val RESUME_ABOVE = 1024 * MB
  const val WARN_BELOW = 2048 * MB

  /**
   * The level for [freeBytes], given the [previous] level (for the resume margin) and the [growthBytes] the index still needs.
   * Pure: the tests drive it with numbers.
   */
  fun level(freeBytes: Long, previous: Level = Level.OK, growthBytes: Long = 0L): Level = when {
    freeBytes < LOW_BELOW -> Level.LOW
    previous == Level.LOW && freeBytes < RESUME_ABOVE -> Level.LOW
    freeBytes < WARN_BELOW -> Level.WARN
    growthBytes > 0 && freeBytes - growthBytes < LOW_BELOW -> Level.WARN
    else -> Level.OK
  }

  /** Free bytes on the drive that holds [dir] (the index lives there); null when the system will not say. */
  fun freeBytes(dir: File): Long? {
    var d: File? = dir.absoluteFile
    while (d != null && !d.exists()) d = d.parentFile
    val free = d?.usableSpace ?: return null
    return free.takeIf { it > 0L }   // 0 is what Java answers when it cannot ask
  }
}
