package trawl.desktop

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Whether a `.ts`, `.mts` or `.m2ts` file is a video. `.ts` is also TypeScript, and a developer's folders hold tens of thousands of them, so
 * the extension alone says nothing: the bytes decide. The shared rules ([com.craneai.findr.index.Rules.classify]) still call these
 * extensions video; the phone gets its types from MediaStore, which has read the file already, so only the desktop crawl asks (see [DocFormats.classify]).
 *
 * An MPEG transport stream is a run of 188-byte packets that each start with the sync byte 0x47. A Blu-ray / AVCHD `.m2ts` puts a 4-byte
 * timestamp in front of every packet (192 bytes), so its sync bytes sit at 4, 196, 388. Two packets must agree (three when the head is
 * long enough to show a third); a text file does not carry the same byte at three places 188 bytes apart.
 */
object TransportStream {
  /** Enough for three 188-byte packets (377 bytes) or three 192-byte ones (389 bytes), with room to spare. */
  const val HEAD_BYTES = 600
  private const val SYNC = 0x47
  private val EXTENSIONS = setOf("ts", "mts", "m2ts")

  /** True for a name whose extension may be a video or TypeScript: only these are looked inside. */
  fun isAmbiguous(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

  /** Whether the first [length] bytes of [head] are a transport stream's (188-byte packets, or 192-byte M2TS packets). */
  fun looksLike(head: ByteArray, length: Int = head.size): Boolean {
    fun sync(at: Int) = at < length && (head[at].toInt() and 0xff) == SYNC
    fun run(offset: Int, packet: Int): Boolean {
      if (!sync(offset) || !sync(offset + packet)) return false
      val third = offset + 2 * packet
      return third >= length || sync(third)   // a head too short to hold a third packet is judged on two
    }
    return run(0, 188) || run(4, 192)
  }

  /** Reads at most [HEAD_BYTES] of [file] and judges them. A file that cannot be read says nothing, so it is not a video here. */
  fun check(file: Path): Boolean = try {
    Files.newInputStream(file).use { input ->
      val buf = ByteArray(HEAD_BYTES)
      var n = 0
      while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r <= 0) break; n += r }
      looksLike(buf, n)
    }
  } catch (_: IOException) { false }
}
