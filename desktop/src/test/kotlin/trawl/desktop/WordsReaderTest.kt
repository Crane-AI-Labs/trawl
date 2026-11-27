package trawl.desktop

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.OcrLine
import com.craneai.findr.index.CrawlRecord
import com.craneai.findr.index.IndexStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class WordsReaderTest {
  @Test fun theWordsPassStoresAShortCaptionInFtsWithoutTheNoise() {
    val directory = Files.createTempDirectory("trawl-short-words").toFile()
    val store = IndexStore.open(directory, log = {})
    try {
      val picture = File(directory, "caption.png")
      store.upsertFromCrawl(listOf(CrawlRecord("desktop", 1, picture.toURI().toString(), "", picture.name, FileKind.SCREENSHOT, null, 1, 1)))
      val fileId = store.knownMediaStoreIds("desktop").getValue(1)
      val reader = object : Ocr {
        override val name = "caption"
        override fun read(image: File) = "SALE\n50%\n|l1"
        override fun readLines(image: File) = read(image).lines().map { OcrLine(it, 0.97f, 0f, 0.08f, 0.3f, "en", 0.95f) }
        override fun close() = Unit
      }
      WordsReader(store) { reader }.read(store.fileById(fileId)!!)
      assertEquals(1L, store.read { it.long("SELECT count(*) FROM fts JOIN segments s ON s.id = fts.rowid WHERE fts MATCH 'sale' AND s.file = ?", fileId) })
      assertEquals("SALE", store.read { it.text("SELECT body FROM fts JOIN segments s ON s.id = fts.rowid WHERE s.kind = 'ocr' AND s.file = ?", fileId) })
      assertEquals(0L, store.read { it.long("SELECT count(*) FROM segments WHERE file = ? AND vec IS NOT NULL", fileId) })
    } finally {
      store.close()
      directory.deleteRecursively()
    }
  }
}
