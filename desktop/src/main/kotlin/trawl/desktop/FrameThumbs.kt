package trawl.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * The still picture on a video's result row: the frame at the moment the search matched (a keyframe, as on the phone), small, drawn
 * beside the name. It reuses [MediaDecoder.frame], the same call the indexer embeds frames with, but through its OWN decoder (its own
 * helper process), so a screen of rows never waits behind the index worker and the worker never waits behind a screen.
 *
 * Frames live only in memory (a small LRU), never on disk. A video the decoder cannot open is remembered as "no frame" and tried again
 * after [RETRY_MS], so a decoder that was busy or a file still being copied is not given up on for the whole session.
 */
class FrameThumbs(
  private val decoder: () -> MediaDecoder?,
  private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
  private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "trawl-frames").apply { isDaemon = true } }
  private var dec: MediaDecoder? = null
  private var decoderFailedAt = 0L
  private val cache = object : LinkedHashMap<String, ImageBitmap>(KEEP, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > KEEP
  }
  private val failedAt = HashMap<String, Long>()

  private fun key(file: File, momentMs: Long, side: Int) = "${file.absolutePath}|${file.lastModified()}|${bucket(momentMs)}|$side"

  /** The frame if it is already decoded (no waiting), so a row scrolling back into view draws at once. */
  @Synchronized fun peek(file: File, momentMs: Long, side: Int = SIDE): ImageBitmap? = cache[key(file, momentMs, side)]

  /** True while a recent attempt at this frame failed, so the row shows its icon without asking the decoder again. */
  @Synchronized fun recentlyFailed(file: File, momentMs: Long, side: Int = SIDE): Boolean =
    failedAt[key(file, momentMs, side)]?.let { clock() - it < RETRY_MS } == true

  /** The frame at about [momentMs], or null when this computer cannot give one. Blocking (one at a time): call it off the UI thread. */
  fun load(file: File, momentMs: Long, side: Int = SIDE): ImageBitmap? {
    peek(file, momentMs, side)?.let { return it }
    if (recentlyFailed(file, momentMs, side)) return null
    return pool.submit<ImageBitmap?> { decode(file, momentMs, side) }.get()
  }

  private fun decode(file: File, momentMs: Long, side: Int): ImageBitmap? {
    val k = key(file, momentMs, side)
    synchronized(this) { cache[k]?.let { return it } }
    val bmp = try {
      val d = decoderOrNull()
      d?.frame(file, bucket(momentMs), side)?.let { ImageIO.read(ByteArrayInputStream(it)) }?.toComposeImageBitmap()
    } catch (_: Throwable) { null }
    synchronized(this) { if (bmp != null) { cache[k] = bmp; failedAt.remove(k) } else failedAt[k] = clock() }
    return bmp
  }

  /** The helper process, started on first use; a computer with no decoder is asked again only every [RETRY_MS]. */
  private fun decoderOrNull(): MediaDecoder? = synchronized(this) {
    if (dec == null && (decoderFailedAt == 0L || clock() - decoderFailedAt >= RETRY_MS)) {
      dec = runCatching(decoder).getOrNull()
      if (dec == null) decoderFailedAt = clock()
    }
    dec
  }

  override fun close() {
    pool.shutdownNow()
    synchronized(this) { runCatching { dec?.close() }; dec = null; cache.clear(); failedAt.clear() }
  }

  companion object {
    const val SIDE = 160
    const val KEEP = 120
    const val RETRY_MS = 30_000L
    private const val BUCKET_MS = 500L

    /** Moments within half a second share one picture (a keyframe is not nearer than that anyway). */
    fun bucket(ms: Long): Long = (ms.coerceAtLeast(0) / BUCKET_MS) * BUCKET_MS

    /**
     * Which moment a row's picture is taken at: the hit moment when the search matched one, else a tenth of the way in (the first
     * instant of a video is often black), capped at ten seconds; 0 when the length is not known.
     */
    fun momentFor(startMs: Long?, durationMs: Long?): Long = startMs ?: (durationMs?.div(10)?.coerceAtMost(10_000L) ?: 0L)

    /**
     * Whether a row may show a frame at all: a video only, never one the person marked private or one a blur hides. Those rows keep
     * their icon, so a picture of a private file is never even decoded for the list.
     */
    fun mayShow(file: IndexedFile, hidden: Boolean): Boolean = file.kind == FileKind.VIDEO && !file.sensitive && !hidden
  }
}
