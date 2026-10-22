package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.core.PrivateLook
import com.craneai.findr.index.PrivacyRules
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import trawl.desktop.privacy.Blurred
import trawl.desktop.privacy.ModelFailure
import trawl.desktop.privacy.OrtPrivateScorer
import trawl.desktop.privacy.PictureScorer
import trawl.desktop.privacy.PreviewBlur
import trawl.desktop.privacy.PrivacyIndex
import trawl.desktop.privacy.PrivacyPackFiles
import trawl.desktop.privacy.PrivacyPass

/** Made-up pictures only: generated here, never a real photo. */
class PrivacyPassTest {
  private fun row(id: Long, score: Float? = null, uri: String = "file:///nowhere/$id.png") = IndexedFile(
    id = id, uri = uri, relativePath = "p/$id.png", name = "$id.png", kind = FileKind.IMAGE, mimeType = "image/png", sizeBytes = 10, modifiedMillis = 1, looksPrivate = score,
  )

  private class Ix(rows: List<IndexedFile>) : PrivacyIndex {
    val todo = ArrayDeque(rows)
    val saved = LinkedHashMap<Long, Float?>()
    var model: String? = null
    override fun applyRules(rules: PrivacyRules) = 0
    override fun setMarked(fileId: Long, on: Boolean): PrivacyRules? = null
    override fun takeLearnedRules(): PrivacyRules? = null
    override fun isSensitive(fileId: Long) = false
    override fun sensitiveCount() = 0
    override fun nextForPrivateScore(limit: Int, kinds: Set<FileKind>): List<IndexedFile> = todo.filter { it.id !in saved }.take(limit)
    override fun saveLooksPrivate(fileId: Long, score: Float?) { saved[fileId] = score }
    override fun ensurePrivateModel(model: String): Boolean { this.model = model; return false }
    override fun clearLooksPrivate() = 0
    override fun privateScoreCounts() = saved.size to todo.size
  }

  private class Scorer(val available: Boolean = true, val answer: (File) -> Float? = { 0.5f }) : PictureScorer {
    var released = 0
    override fun available() = available
    override fun score(file: File): Float? = answer(file)
    override fun release() { released++ }
  }

  private fun files(n: Int): Pair<File, List<IndexedFile>> {
    val dir = Files.createTempDirectory("pp").toFile()
    val rows = (1..n).map { i -> File(dir, "$i.png").also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", it) }.let { row(i.toLong(), uri = it.toURI().toString()) } }
    return dir to rows
  }

  @Test fun `every picture is scored once and the model is recorded`() {
    val (_, rows) = files(5)
    val ix = Ix(rows)
    val sc = Scorer { f -> f.nameWithoutExtension.toFloat() / 10f }
    assertEquals(5, PrivacyPass(ix, sc).run())
    assertEquals(PrivateLook.MODEL, ix.model)
    assertEquals(mapOf<Long, Float?>(1L to 0.1f, 2L to 0.2f, 3L to 0.3f, 4L to 0.4f, 5L to 0.5f), ix.saved)
    assertEquals(1, sc.released, "the model's memory is given back")
  }

  @Test fun `without the pack nothing is scored`() {
    val (_, rows) = files(2)
    val ix = Ix(rows)
    assertEquals(0, PrivacyPass(ix, Scorer(available = false)).run())
    assertTrue(ix.saved.isEmpty())
  }

  @Test fun `a picture that cannot be read is marked unreadable, not retried and not fatal`() {
    val (_, rows) = files(3)
    val ix = Ix(rows + row(9, uri = "file:///does/not/exist.png"))
    assertEquals(4, PrivacyPass(ix, Scorer { if (it.name == "2.png") throw IllegalStateException("corrupt") else 0.2f }).run())
    assertTrue(ix.saved.containsKey(2L) && ix.saved[2L] == null)
    assertTrue(ix.saved.containsKey(9L) && ix.saved[9L] == null)
    assertEquals(0.2f, ix.saved[1L])
  }

  @Test fun `a model that will not run stops the pass without marking every picture unreadable`() {
    val (_, rows) = files(4)
    val ix = Ix(rows)
    val pass = PrivacyPass(ix, Scorer { if (it.name == "2.png") throw ModelFailure(RuntimeException("x")) else 0.1f })
    pass.run()
    assertTrue(pass.modelFailed)
    assertEquals(setOf(1L), ix.saved.keys)
  }

  @Test fun `the pass stops when asked`() {
    val (_, rows) = files(6)
    val ix = Ix(rows)
    var n = 0
    PrivacyPass(ix, Scorer { 0.1f }, stopped = { n++ >= 3 }).run()
    assertTrue(ix.saved.size < 6)
  }

  @Test fun `only file uris are scored`() {
    assertNotNull(PrivacyPass.fileOfUri(row(1, uri = "file:///a/b.png")))
    assertEquals(null, PrivacyPass.fileOfUri(row(1, uri = "content://media/1")))
    assertEquals(null, PrivacyPass.fileOfUri(row(1, uri = "not a uri at all")))
  }

  // ---- the blur decision ---------------------------------------------------------------------------------------------

  @Test fun `a picture blurs at the main window threshold and not below, and nothing unscored blurs`() {
    val b = PreviewBlur(enabled = true)
    assertTrue(b.hides(row(1, 0.95f)))
    assertTrue(b.hides(row(2, PrivateLook.MAIN_APP_THRESHOLD)))
    assertFalse(b.hides(row(3, 0.93f)))
    assertFalse(b.hides(row(4, null)))
  }

  @Test fun `with the blur off nothing is hidden whatever the score`() {
    assertFalse(PreviewBlur(enabled = false).hides(row(1, 0.99f)))
  }

  @Test fun `a reveal is per picture, can be undone, and the count follows`() {
    val b = PreviewBlur(enabled = true)
    val rows = listOf(row(1, 0.99f), row(2, 0.97f), row(3, 0.1f))
    assertEquals(2, b.hiddenCount(rows))
    assertTrue(b.reveal(rows[0]))
    assertEquals(1, b.hiddenCount(rows))
    assertTrue(b.anyRevealed(rows))
    b.cover(rows[0])
    assertEquals(2, b.hiddenCount(rows))
    b.revealAll(rows)
    assertEquals(0, b.hiddenCount(rows))
    b.coverAll(rows)
    assertEquals(2, b.hiddenCount(rows))
    assertFalse(b.anyRevealed(rows))
  }

  @Test fun `a reveal the gate refuses does nothing`() {
    var denied = 0
    val b = PreviewBlur(enabled = true, allowReveal = { false }, onDenied = { denied++ })
    val r = row(1, 0.99f)
    assertFalse(b.reveal(r))
    assertFalse(b.revealAll(listOf(r)))
    assertTrue(b.hides(r))
    assertEquals(2, denied)
  }

  @Test fun `the first blur announces itself once`() {
    var n = 0
    val b = PreviewBlur(enabled = true, onFirstBlur = { n++ })
    b.blurShown(); b.blurShown()
    assertEquals(1, n)
  }

  // ---- the blurred pixels ----------------------------------------------------------------------------------------------

  private fun stripes(w: Int, h: Int) = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { im ->
    for (y in 0 until h) for (x in 0 until w) im.setRGB(x, y, if ((x + y) % 2 == 0) Color.BLACK.rgb else Color.WHITE.rgb)
  }

  @Test fun `blur keeps the shape and the long side at the shown size`() {
    val out = Blurred.of(stripes(640, 320))
    assertEquals(Blurred.SHOWN, out.width)
    assertEquals(Blurred.SHOWN / 2, out.height)
    val tall = Blurred.of(stripes(100, 400))
    assertEquals(Blurred.SHOWN, tall.height)
  }

  @Test fun `fine detail does not survive the blur`() {
    // One pixel checkerboard: sharp it is maximum contrast, blurred it must be a flat grey.
    val out = Blurred.of(stripes(480, 480))
    var min = 255; var max = 0
    for (y in 0 until out.height) for (x in 0 until out.width) {
      val g = (out.getRGB(x, y) shr 8) and 0xff
      min = minOf(min, g); max = maxOf(max, g)
    }
    assertTrue(max - min < 40, "contrast left after the blur: ${max - min}")
  }

  @Test fun `two pictures that differ only in fine detail blur to the same thing`() {
    val a = BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB).also { im -> im.createGraphics().apply { color = Color(180, 60, 60); fillRect(0, 0, 240, 240); dispose() } }
    val b = BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB).also { im ->
      im.createGraphics().apply { color = Color(180, 60, 60); fillRect(0, 0, 240, 240); dispose() }
      for (y in 0 until 240 step 4) for (x in 0 until 240 step 4) im.setRGB(x, y, Color(150, 90, 90).rgb)   // a fine dotted overlay, mean nearly the same
    }
    val ba = Blurred.of(a); val bb = Blurred.of(b)
    var worst = 0
    for (y in 0 until ba.height) for (x in 0 until ba.width) worst = maxOf(worst, abs(((ba.getRGB(x, y) shr 16) and 0xff) - ((bb.getRGB(x, y) shr 16) and 0xff)))
    assertTrue(worst < 12, "pixel difference $worst")
  }

  // ---- the real model on made-up pictures (needs the pack; skipped where it is not) ---------------------------------------

  private val packDir: File? = (System.getenv("TRAWL_PRIVACY_PACK_DIR") ?: "/mnt/data/trawl-fixtures/findr-private-models").let(::File).takeIf { it.isDirectory }

  private fun madeUp(kind: Int): BufferedImage = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB).also { im ->
    val g = im.createGraphics()
    when (kind) {
      0 -> { g.paint = java.awt.GradientPaint(0f, 0f, Color(120, 180, 255), 0f, 480f, Color(40, 120, 40)); g.fillRect(0, 0, 640, 480); g.color = Color(250, 220, 40); g.fillOval(480, 40, 80, 80) }   // sky, field, sun
      1 -> { g.color = Color.WHITE; g.fillRect(0, 0, 640, 480); g.color = Color.BLACK; for (i in 0 until 14) g.drawString("Invoice line $i    ${i * 37}.00", 40, 40 + i * 30) }                          // a document
      2 -> { g.color = Color(0, 90, 160); g.fillRect(0, 0, 640, 480); g.color = Color.WHITE; for (i in 0 until 6) g.fillRect(40 + i * 90, 400 - i * 50, 60, i * 50) }                                  // a chart
      else -> { g.color = Color(224, 172, 140); g.fillRect(0, 0, 640, 480); g.color = Color(190, 130, 100); g.fillOval(160, 90, 320, 300) }                                                    // flat skin-toned shapes
    }
    g.dispose()
  }

  @Test fun `the real model scores made-up pictures between 0 and 1, repeatably, and none of the ordinary ones blur`() {
    val dir = packDir
    if (dir == null) { println("SKIPPED: no Privacy pack on this machine (set TRAWL_PRIVACY_PACK_DIR)"); return }
    val model = File(dir, "nsfw-384/nsfw-384.onnx")
    val scorer = OrtPrivateScorer(model)
    assertTrue(scorer.available())
    try {
      val scores = (0..3).map { scorer.score(madeUp(it)) }
      println("PRIVACY-PACK made-up scores (landscape, document, chart, flat skin-tones): $scores")
      assertTrue(scores.all { it in 0f..1f })
      assertEquals(scores, (0..3).map { scorer.score(madeUp(it)) }, "the same picture must score the same")
      for (i in 0..2) assertFalse(PrivateLook.blurs(scores[i], PrivateLook.MAIN_APP_THRESHOLD), "ordinary picture $i would blur: ${scores[i]}")
    } finally {
      scorer.release()
    }
  }

  @Test fun `the real model through the whole pass writes a score for each file and nothing for the unreadable one`() {
    val dir = packDir
    if (dir == null) { println("SKIPPED: no Privacy pack on this machine"); return }
    val folder = Files.createTempDirectory("pp-real").toFile()
    val rows = (0..2).map { i -> File(folder, "p$i.png").also { ImageIO.write(madeUp(i), "png", it) }.let { row((i + 1).toLong(), uri = it.toURI().toString()) } }
    val broken = File(folder, "broken.png").also { it.writeText("this is not a png") }
    val ix = Ix(rows + row(7, uri = broken.toURI().toString()))
    val done = PrivacyPass(ix, OrtPrivateScorer(File(dir, "nsfw-384/nsfw-384.onnx"))).run()
    assertEquals(4, done)
    assertTrue(ix.saved[7L] == null && ix.saved.containsKey(7L))
    assertTrue((1L..3L).all { ix.saved[it] != null })
    assertEquals(PrivateLook.MODEL, ix.model)
  }

  @Test fun `the pack path is where the add-on installs it`() {
    val d = File("/m")
    assertEquals(File("/m/private/nsfw-384/nsfw-384.onnx"), PrivacyPackFiles.modelFile(d))
  }
}
