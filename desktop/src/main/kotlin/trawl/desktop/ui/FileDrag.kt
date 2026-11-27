package trawl.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import java.awt.BasicStroke
import java.awt.Canvas
import java.awt.Color as AwtColor
import java.awt.Component
import java.awt.Container
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.dnd.DnDConstants
import java.awt.dnd.DragGestureListener
import java.awt.dnd.DragSource
import java.awt.dnd.DragSourceAdapter
import java.awt.dnd.DragSourceDropEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import trawl.desktop.FileSubType
import trawl.desktop.Os
import trawl.desktop.Platform
import trawl.desktop.shell.MacNative

/** Holds the files armed for native OS drag-and-drop from the current window (main window or Quick Search). */
class FileDragHost(
  internal val window: Window? = null,
  val onDragStart: () -> Unit = {},
  val onDragEnd: () -> Unit = {},
) {
  private val lock = Any()
  private var armedAtNs: Long = 0L
  private var armedFiles: List<File> = emptyList()
  @Volatile internal var macDragActive: Boolean = false

  internal val useNativeMacDrag: Boolean
    get() = Platform.os == Os.MAC && window != null && MacNative.available

  fun arm(files: List<File>) {
    synchronized(lock) {
      if (files.isEmpty()) {
        armedFiles = emptyList()
        armedAtNs = 0L
      } else {
        armedFiles = files
        armedAtNs = System.nanoTime()
      }
    }
  }

  fun disarm() = synchronized(lock) {
    armedFiles = emptyList()
    armedAtNs = 0L
  }

  /** Returns the currently armed files (filtered for existence only once a real drag starts) if armed within the last 3 seconds. */
  fun takeArmed(): List<File>? {
    val raw = synchronized(lock) {
      val f = armedFiles
      if (f.isEmpty()) return null
      val ageMs = ((System.nanoTime() - armedAtNs).coerceAtLeast(0L)) / 1_000_000L
      armedFiles = emptyList()
      armedAtNs = 0L
      if (ageMs <= ARM_WINDOW_MS) f else null
    } ?: return null
    return raw.filter { it.exists() }.takeIf { it.isNotEmpty() }
  }

  /** Starts a native Cocoa NSDraggingSession carrying genuine NSURL (public.file-url) items for WhatsApp/Chrome/Finder. */
  internal fun startNativeMacDrag(): Boolean {
    val w = window ?: return false
    if (macDragActive) return false
    val files = takeArmed() ?: return false
    macDragActive = true
    onDragStart()
    val png = runCatching {
      val ghost = renderDragGhost(files)
      ByteArrayOutputStream().also { ImageIO.write(ghost, "png", it) }.toByteArray()
    }.getOrNull()
    val ok = MacNative.startFileDrag(w, files, png) {
      macDragActive = false
      onDragEnd()
    }
    if (!ok) {
      macDragActive = false
      onDragEnd()
    }
    return ok
  }

  companion object {
    private const val ARM_WINDOW_MS = 3_000L
  }
}

val LocalFileDrag = compositionLocalOf<FileDragHost?> { null }

/**
 * Standard OS transferable carrying real file objects ([DataFlavor.javaFileListFlavor]).
 * On Windows and macOS fallback, omits [DataFlavor.stringFlavor] (matching [trawl.desktop.AwtClipboard]) so chat/mail apps
 * like WhatsApp, Slack, Discord, and Chrome attach the actual file via `CF_HDROP` instead of pasting raw path text.
 * On Linux, includes [DataFlavor.stringFlavor] as a fallback for text/terminal drop targets alongside `text/uri-list`.
 */
internal class FileListTransferable(
  private val files: List<File>,
  os: Os = Platform.os,
) : Transferable {
  private val includePathsAsText: Boolean = os == Os.LINUX
  private val flavors: Array<DataFlavor> = if (includePathsAsText) {
    arrayOf(DataFlavor.javaFileListFlavor, DataFlavor.stringFlavor)
  } else {
    arrayOf(DataFlavor.javaFileListFlavor)
  }

  override fun getTransferDataFlavors(): Array<DataFlavor> = flavors

  override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
    flavors.any { it.equals(flavor) }

  override fun getTransferData(flavor: DataFlavor): Any = when {
    flavor.equals(DataFlavor.javaFileListFlavor) -> files
    includePathsAsText && flavor.equals(DataFlavor.stringFlavor) -> files.joinToString("\n") { it.absolutePath }
    else -> throw UnsupportedFlavorException(flavor)
  }
}

/** Finds the AWT canvas (Compose's SkiaLayer) receiving real pointer events inside [window]. */
private fun findDragTargets(window: Window): List<Component> {
  val found = ArrayList<Component>()
  fun walk(c: Component) {
    if (c is Canvas || c.javaClass.name.contains("Skia", ignoreCase = true)) found += c
    if (c is Container) c.components.forEach(::walk)
  }
  walk(window)
  if (found.isEmpty()) window.focusTraversalPolicy?.getInitialComponent(window)?.let { found += it }
  if (found.isEmpty()) found += window
  return found.distinct()
}

/** Renders a crisp 2x Retina drag-ghost card (<1 ms, zero blocking disk I/O on EDT) showing color-coded format badge, filename, and multi-select pill. */
internal fun renderDragGhost(files: List<File>): BufferedImage {
  val w = 248
  val h = 46
  val scale = 2
  val img = BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_ARGB)
  val g = img.createGraphics()
  try {
    g.scale(scale.toDouble(), scale.toDouble())
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)

    // Card background + border
    g.color = AwtColor(28, 26, 23, 240)
    g.fillRoundRect(1, 1, w - 2, h - 2, 12, 12)
    g.color = AwtColor(245, 200, 76, 230)
    g.stroke = BasicStroke(1.5f)
    g.drawRoundRect(1, 1, w - 3, h - 3, 12, 12)

    val first = files.first()
    val ext = FileSubType.extensionOf(first.name).orEmpty()
    val badgeColor = when (ext) {
      "pdf" -> AwtColor(0xF8, 0x71, 0x71)
      "docx", "doc", "odt", "rtf" -> AwtColor(0x60, 0xA5, 0xFA)
      "xlsx", "xls", "ods", "csv" -> AwtColor(0x4A, 0xDE, 0x80)
      "pptx", "ppt", "odp", "key" -> AwtColor(0xFB, 0x92, 0x3C)
      "mp4", "mov", "mkv", "webm" -> AwtColor(0xC0, 0x84, 0xFC)
      "wav", "mp3", "m4a", "ogg", "flac" -> AwtColor(0x38, 0xBD, 0xF8)
      "jpg", "jpeg", "png", "webp", "heic", "bmp", "gif" -> AwtColor(0xF5, 0xC8, 0x4C)
      else -> AwtColor(0x2D, 0xD4, 0xBF)
    }

    // Leading instant vector badge (zero blocking disk ImageIO.read on the AWT EDT so drag starts instantaneously)
    g.color = AwtColor(badgeColor.red, badgeColor.green, badgeColor.blue, 42)
    g.fillRoundRect(8, 7, 34, 32, 7, 7)
    g.color = badgeColor
    g.drawRoundRect(8, 7, 34, 32, 7, 7)
    g.font = Font("SansSerif", Font.BOLD, 10)
    val tag = (ext.uppercase().take(4)).ifBlank { "FILE" }
    val fmTag = g.fontMetrics
    g.drawString(tag, 8 + (34 - fmTag.stringWidth(tag)) / 2, 7 + (32 + fmTag.ascent - fmTag.descent) / 2)

    // File name (+ subtle subtitle when multiple selected)
    val textX = 50
    val countReserve = if (files.size > 1) 38 else 12
    val maxTextW = w - textX - countReserve
    g.font = Font("SansSerif", Font.BOLD, 12)
    g.color = AwtColor(248, 245, 238)
    val fm = g.fontMetrics
    var title = first.name
    while (title.length > 4 && fm.stringWidth(title) > maxTextW) title = title.dropLast(2) + "\u2026"
    g.drawString(title, textX, if (files.size > 1) 20 else 27)
    if (files.size > 1) {
      g.font = Font("SansSerif", Font.PLAIN, 10)
      g.color = AwtColor(196, 190, 178)
      g.drawString("${files.size} files selected", textX, 35)
      // Count pill
      val pill = files.size.toString()
      g.font = Font("SansSerif", Font.BOLD, 11)
      val pw = maxOf(22, g.fontMetrics.stringWidth(pill) + 10)
      val px = w - pw - 8
      g.color = AwtColor(245, 200, 76)
      g.fillRoundRect(px, 12, pw, 22, 11, 11)
      g.color = AwtColor(24, 22, 18)
      g.drawString(pill, px + (pw - g.fontMetrics.stringWidth(pill)) / 2, 27)
    }
  } finally {
    g.dispose()
  }
  return img
}

/**
 * Installs an AWT [DragSource] on [window]'s Compose canvas so dragging any armed result row or photo tile out of Trawl
 * or Quick Search hands genuine OS files to Finder, Mail, Slack, Explorer, etc.
 */
@Composable
fun ProvideFileDrag(
  window: Window?,
  onDragStart: () -> Unit = {},
  onDragEnd: () -> Unit = {},
  content: @Composable () -> Unit,
) {
  val startCb by rememberUpdatedState(onDragStart)
  val endCb by rememberUpdatedState(onDragEnd)
  val host = remember(window) { FileDragHost(window = window, onDragStart = { startCb() }, onDragEnd = { endCb() }) }
  DisposableEffect(window, host) {
    val recognizers = ArrayList<java.awt.dnd.DragGestureRecognizer>()
    // On macOS, install the local mouse monitor early so native Cocoa NSDraggingSession always receives the
    // authentic live hardware NSEvent + eventNumber, bypassing AWT's legacy NSFilenamesPboardType-only drag.
    if (host.useNativeMacDrag) {
      MacNative.installDragMonitor()
    }
    if (window != null && !GraphicsEnvironment.isHeadless() && !host.useNativeMacDrag) {
      runCatching {
        val ds = DragSource.getDefaultDragSource()
        val sourceListener = object : DragSourceAdapter() {
          override fun dragDropEnd(dsde: DragSourceDropEvent?) {
            host.onDragEnd()
          }
        }
        val gestureListener = DragGestureListener { dge ->
          val files = host.takeArmed() ?: return@DragGestureListener
          host.onDragStart()
          runCatching {
            if (Platform.os == Os.LINUX) {
              (java.awt.datatransfer.SystemFlavorMap.getDefaultFlavorMap() as java.awt.datatransfer.SystemFlavorMap)
                .setNativesForFlavor(DataFlavor.javaFileListFlavor, arrayOf("text/uri-list"))
            }
            val transferable = FileListTransferable(files)
            if (DragSource.isDragImageSupported()) {
              val ghost = renderDragGhost(files)
              dge.startDrag(DragSource.DefaultCopyDrop, ghost, Point(-20, -20), transferable, sourceListener)
            } else {
              dge.startDrag(DragSource.DefaultCopyDrop, transferable, sourceListener)
            }
          }.onFailure {
            host.onDragEnd()
          }
        }
        for (target in findDragTargets(window)) {
          recognizers += ds.createDefaultDragGestureRecognizer(target, DnDConstants.ACTION_COPY, gestureListener)
        }
      }
    }
    onDispose {
      recognizers.forEach { r -> runCatching { r.component = null } }
      host.disarm()
    }
  }
  CompositionLocalProvider(LocalFileDrag provides host, content = content)
}

/**
 * Arms [filesProvider] for native OS drag-and-drop on primary mouse press without consuming clicks, double-clicks, or scrolling.
 * On macOS, crossing the 6 px drag threshold starts a native Cocoa `NSDraggingSession` carrying `NSURL` (`public.file-url`) items.
 * Returns an empty list from [filesProvider] to block dragging hidden or sensitive items.
 */
fun Modifier.dragFiles(key: Any?, filesProvider: () -> List<File>): Modifier = composed {
  val host = LocalFileDrag.current ?: return@composed this
  val provider by rememberUpdatedState(filesProvider)
  this.pointerInput(host, key) {
    awaitPointerEventScope {
      var pressPos: androidx.compose.ui.geometry.Offset? = null
      while (true) {
        val ev = awaitPointerEvent(PointerEventPass.Initial)
        val change = ev.changes.firstOrNull()
        when (ev.type) {
          PointerEventType.Press -> if (ev.buttons.isPrimaryPressed) {
            host.arm(provider())
            pressPos = change?.position
          }
          PointerEventType.Move -> {
            val start = pressPos
            if (start != null && change != null && ev.buttons.isPrimaryPressed && host.useNativeMacDrag) {
              if ((change.position - start).getDistance() >= 6f) {
                pressPos = null
                if (host.startNativeMacDrag()) {
                  change.consume()
                }
              }
            }
          }
          PointerEventType.Release -> {
            pressPos = null
            host.disarm()
          }
          else -> {}
        }
      }
    }
  }
}
