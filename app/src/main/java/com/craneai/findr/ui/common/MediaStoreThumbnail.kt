package com.craneai.findr.ui.common

import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.pxOrElse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A grid thumbnail that comes from the system's own thumbnail cache instead of decoding the original file.
 *
 * The slow part of scrolling a photo grid was never drawing: it was Coil decoding 12 MP JPEGs, 1080x2400 screenshots and
 * a video frame per cell on a phone with six little cores. `ContentResolver.loadThumbnail` asks MediaProvider, which keeps
 * small thumbnails (and uses the JPEG's embedded one when it has it), so a cell costs a small read instead of a decode.
 * Same idea Fossify Gallery, Aves and the Android Photo Picker use; no code taken from them.
 *
 * It only handles [MediaThumb]; the picture on screen falls back to the plain Coil path if MediaProvider cannot make one.
 */
data class MediaThumb(val uri: String)

/**
 * Whether the media grid is flinging too fast for anyone to look at its cells. Thumbnail fetches wait while it is (the
 * classic gallery technique): a fast fling binds and drops dozens of cells, and their decodes, MediaProvider calls and
 * arrival redraws competed with the frames on the A06. Cells still on screen when it slows load then; cells that
 * scrolled past cancel their wait; memory-cache hits never get here. Set by the grid (SearchScreen).
 */
object GridFling {
  @Volatile var fast = false
  /** Cells per second of scrolling above which the grid counts as flinging (~2 screens a second on a phone). */
  const val FAST_CELLS_PER_S = 40.0
}

class MediaThumbKeyer : Keyer<MediaThumb> {
  // The size is part of the key so a bigger cell later does not reuse a thumbnail that is too small for it.
  override fun key(data: MediaThumb, options: Options): String = "mediathumb:${data.uri}:${options.size.width}x${options.size.height}"
}

class MediaThumbFetcher(private val data: MediaThumb, private val options: Options) : Fetcher {
  override suspend fun fetch(): FetchResult {
    while (GridFling.fast) delay(32)   // cancelled if the cell leaves the screen first
    UiCounters.thumbLoads.incrementAndGet()
    val px = Size(options.size.width.pxOrElse { DEFAULT_PX }.coerceIn(MIN_PX, MAX_PX), options.size.height.pxOrElse { DEFAULT_PX }.coerceIn(MIN_PX, MAX_PX))
    val bitmap: Bitmap = coroutineScope {
      val signal = CancellationSignal()
      // A cell that scrolled away cancels its request; tell MediaProvider so it stops working on it.
      val watcher = launch { try { awaitCancellation() } finally { signal.cancel() } }
      try {
        withContext(decoders) {
          val soft = options.context.contentResolver.loadThumbnail(Uri.parse(data.uri), px, signal)
          // A software bitmap is uploaded to the GPU by the RenderThread the first time it is drawn (Bitmap#prepareToDraw,
          // ~0.9 ms per 360 px thumbnail on the A06, mid-fling). As a hardware bitmap the upload happens here, off the frame.
          soft.copy(Bitmap.Config.HARDWARE, false)?.also { soft.recycle() } ?: soft
        }
      } finally {
        watcher.cancel()
      }
    }
    return ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
  }

  class Factory : Fetcher.Factory<MediaThumb> {
    override fun create(data: MediaThumb, options: Options, imageLoader: ImageLoader): Fetcher = MediaThumbFetcher(data, options)
  }

  private companion object {
    const val DEFAULT_PX = 320
    const val MIN_PX = 64
    const val MAX_PX = 1024

    /** Few at once: a fling binds a dozen cells together, and six little cores should not all be decoding for them. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val decoders: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(3)
  }
}
