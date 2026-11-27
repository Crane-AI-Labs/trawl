package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.KindFilter
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SimilarReturnTest {
  private fun samplePhoto(id: Long, name: String): IndexedFile = IndexedFile(
    id = id,
    uri = "file:///photos/$name",
    name = name,
    relativePath = "Photos/$name",
    kind = FileKind.IMAGE,
    mimeType = "image/jpeg",
    sizeBytes = 123_456L,
    dateMillis = 1_760_000_000_000L,
    modifiedMillis = 1_760_000_000_000L,
    width = 1600,
    height = 1200,
  )

  private fun newController(): Pair<File, AppController> {
    val dir = Files.createTempDirectory("trawl-similar-return").toFile()
    val c = AppController(
      dataDir = dir,
      userFolders = Platform.UserFolders(dir, dir, dir),
      logSink = {},
    )
    return dir to c
  }

  @Test fun findSimilarAndClearSimilarRestoresPriorQueryFilterSelectionAndScroll() {
    val (dir, c) = newController()
    try {
      c.setFilter(KindFilter.PHOTOS)
      c.setQuery("sunset beach")
      c.captureViewport = { Triple(42L, 14, 188) }

      val seed = samplePhoto(42L, "sunset.jpg")
      c.findSimilar(seed)
      assertEquals("", c.query.value)
      assertNotNull(c.view.value.similar)
      assertNull(c.pendingSimilarRestore.value)

      // Chaining Find similar from another result preserves the original pre-similar snapshot
      c.captureViewport = { Triple(99L, 2, 12) }
      c.findSimilar(samplePhoto(99L, "horizon.jpg"))
      assertEquals(99L, c.view.value.similar?.id)

      // User tweaks filter while inspecting similar results; clearing restores the pre-similar state
      c.setFilter(KindFilter.ALL)
      c.clearSimilar()

      assertNull(c.view.value.similar)
      assertEquals("sunset beach", c.query.value)
      assertEquals(KindFilter.PHOTOS, c.filter.value)
      val restore = c.pendingSimilarRestore.value
      assertEquals(
        SimilarReturnState(
          query = "sunset beach",
          filter = KindFilter.PHOTOS,
          subType = null,
          selectedId = 42L,
          scrollIndex = 14,
          scrollOffset = 188,
        ),
        restore,
      )

      c.consumeSimilarRestore(restore!!)
      assertNull(c.pendingSimilarRestore.value)
    } finally {
      c.close()
      dir.deleteRecursively()
    }
  }

  @Test fun typingNewQueryDuringFindSimilarDiscardsSavedReturnSnapshot() {
    val (dir, c) = newController()
    try {
      c.setQuery("old query")
      c.captureViewport = { Triple(7L, 5, 64) }
      c.findSimilar(samplePhoto(7L, "lake.jpg"))

      c.setQuery("fresh search")
      assertNull(c.view.value.similar)
      assertNull(c.pendingSimilarRestore.value)

      c.clearSimilar()
      assertEquals("fresh search", c.query.value)
      assertNull(c.pendingSimilarRestore.value)
    } finally {
      c.close()
      dir.deleteRecursively()
    }
  }

  @Test fun findComposeCanvasPrefersNestedCanvasOverFocusableParentPanels() {
    val root = javax.swing.JPanel().apply { isFocusable = true }
    val inner = javax.swing.JPanel().apply { isFocusable = true }
    val canvas = object : java.awt.Canvas() {}.apply { isFocusable = true }
    inner.add(canvas)
    root.add(inner)
    assertEquals(canvas, trawl.desktop.shell.MacNative.findComposeCanvasIn(root))
  }
}
