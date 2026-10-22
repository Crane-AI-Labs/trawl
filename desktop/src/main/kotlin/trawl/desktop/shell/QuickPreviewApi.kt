package trawl.desktop.shell

import androidx.compose.ui.graphics.ImageBitmap
import com.craneai.findr.core.IndexedFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import trawl.desktop.AppController
import trawl.desktop.PreviewPlayer
import trawl.desktop.Thumbnails
import trawl.desktop.privacy.PrivateThumbnails

/**
 * What the quick panel's preview needs to draw a file large, and nothing more. Every call runs off the UI thread (the caller is a
 * coroutine) and returns null when there is nothing to show. The panel only asks for files it was given as hits, which are never
 * sensitive; a picture the privacy blur is hiding is asked for with [blurred] = true and only ever comes back blurred.
 */
interface QuickPreviewApi {
  /** The picture at about [side] pixels on its long side; [blurred] returns only the blurred pixels (the sharp ones are never decoded for it). */
  suspend fun picture(file: IndexedFile, side: Int, blurred: Boolean): ImageBitmap?

  /** A video's frame at about [momentMs]. */
  suspend fun frame(file: IndexedFile, momentMs: Long, side: Int): ImageBitmap?

  /** The opening text of a document when the hit quotes nothing (the Recent list). */
  suspend fun lead(file: IndexedFile): String?

  /** Plays audio or video from a moment; null where this computer cannot. */
  val player: PreviewPlayer? get() = null

  /** The file on disk, for the details line and the system preview. */
  fun fileOf(file: IndexedFile): File = File(java.net.URI(file.uri))

  /** The system's own full preview (macOS Quick Look), if there is one. */
  val full: FullPreview get() = NoFullPreview

  companion object { val None: QuickPreviewApi = object : QuickPreviewApi {
    override suspend fun picture(file: IndexedFile, side: Int, blurred: Boolean): ImageBitmap? = null
    override suspend fun frame(file: IndexedFile, momentMs: Long, side: Int): ImageBitmap? = null
    override suspend fun lead(file: IndexedFile): String? = null
  } }
}

/** The preview from the real app: the same decoders and caches the main window's preview uses. */
class ControllerPreview(
  private val c: AppController,
  override val full: FullPreview = NoFullPreview,
  override val player: PreviewPlayer? = null,
) : QuickPreviewApi {
  override fun fileOf(file: IndexedFile): File = c.fileOf(file)

  override suspend fun picture(file: IndexedFile, side: Int, blurred: Boolean): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching { if (blurred) PrivateThumbnails.load(fileOf(file), side, blurred = true) else Thumbnails.load(fileOf(file), side) }.getOrNull()
  }

  override suspend fun frame(file: IndexedFile, momentMs: Long, side: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching { c.frames.load(fileOf(file), momentMs, side) }.getOrNull()
  }

  override suspend fun lead(file: IndexedFile): String? = c.leadText(file)
}
