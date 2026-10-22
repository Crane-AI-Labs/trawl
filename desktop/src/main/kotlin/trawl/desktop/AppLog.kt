package trawl.desktop

import com.craneai.findr.diag.LogRing
import java.io.File

/**
 * The app's own log: what Trawl writes about itself while it runs, kept in `logs/trawl.log` in the data folder (the file the
 * "Open log folder" button shows) and on standard error. Every line is made safe BEFORE it is kept, with [Diagnostics.safeLine]:
 * the home folder and anything after it becomes `~`, paths are cut, and a line that carries an error keeps only the code's words
 * and the error's class, never its message. So the file holds no file names, paths or text from the person's files, and a report
 * built from it ([recentErrors]) cannot either.
 */
class AppLog(
  val dir: File,
  private val home: String = System.getProperty("user.home") ?: "",
  private val sink: (String) -> Unit = { System.err.println("[trawl] $it") },
  private val maxBytes: Long = 256 * 1024,
) {
  private val file = File(dir, "trawl.log")
  private val ring = ArrayDeque<String>()

  /** The same safe lines, kept for a problem report and for a crash report ([trawl.desktop.Reporter]). */
  val tail = LogRing()

  /** Keeps [raw] (made safe) in the file and in memory, and passes the safe line to the sink. Never throws. */
  @Synchronized fun log(raw: String) {
    val line = Diagnostics.safeLine(raw, home)
    sink(line)
    tail.add(if (Diagnostics.isError(line)) 'E' else 'I', "trawl", line)
    ring.addLast(line)
    while (ring.size > RING) ring.removeFirst()
    runCatching {
      dir.mkdirs()
      if (file.length() > maxBytes) {
        val old = File(dir, "trawl.log.1")
        old.delete()
        file.renameTo(old)
      }
      file.appendText("${java.time.LocalDateTime.now().withNano(0)} $line\n")
    }
  }

  /** The newest error lines, oldest first, from this run. */
  @Synchronized fun recentErrors(max: Int = 8): List<String> = ring.filter(Diagnostics::isError).takeLast(max)

  private companion object { const val RING = 300 }
}
