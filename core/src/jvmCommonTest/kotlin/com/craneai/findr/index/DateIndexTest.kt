package com.craneai.findr.index

import com.craneai.findr.core.DateRange
import com.craneai.findr.core.FileKind
import com.craneai.findr.core.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** `files.date_ms`: what the crawler stores, how an existing row refreshes it, and how browsing sorts and filters on it. */
class DateIndexTest {
  private lateinit var dir: File
  private lateinit var store: IndexStore

  @Before fun setUp() { dir = Files.createTempDirectory("findr-date").toFile(); store = IndexStore.open(dir, log = {}) }
  @After fun tearDown() { store.close(); dir.deleteRecursively() }

  private val day = 86_400_000L
  private val t0 = 1_700_000_000_000L

  private fun record(id: Long, name: String, mtime: Long, taken: Long = 0, size: Long = 100, kind: FileKind = FileKind.IMAGE) =
    CrawlRecord("external_primary", id, "content://m/$id", "DCIM/", name, kind, null, size, mtime, dateMillis = taken)

  @Test fun aRowWithNoCaptureDateUsesItsModifiedTime() {
    store.upsertFromCrawl(listOf(record(1, "a.jpg", t0)))
    assertEquals(t0, store.recent(FileKind.entries.toSet(), 10).single().dateMillis)
  }

  @Test fun aRowWithACaptureDateUsesIt() {
    store.upsertFromCrawl(listOf(record(1, "a.jpg", t0 + 100 * day, taken = t0)))
    val f = store.recent(FileKind.entries.toSet(), 10).single()
    assertEquals(t0, f.dateMillis)
    assertEquals(t0 + 100 * day, f.modifiedMillis)
  }

  @Test fun aSecondPassWithTheSameBytesRefreshesOnlyTheDate() {
    store.upsertFromCrawl(listOf(record(1, "a.jpg", t0 + 100 * day)))
    val r = store.upsertFromCrawl(listOf(record(1, "a.jpg", t0 + 100 * day, taken = t0)))
    assertEquals(1, r.unchanged)   // not re-read: the vectors stay
    assertEquals(t0, store.recent(FileKind.entries.toSet(), 10).single().dateMillis)
  }

  @Test fun browsingSortsInSql() {
    store.upsertFromCrawl(listOf(
      record(1, "b.jpg", t0 + 2 * day, size = 300), record(2, "A.jpg", t0 + 3 * day, size = 100), record(3, "c.jpg", t0 + 1 * day, size = 200),
    ))
    fun order(o: SortOrder, d: DateRange? = null) = store.recent(FileKind.entries.toSet(), 10, order = o, dates = d).map { it.name }
    assertEquals(listOf("A.jpg", "b.jpg", "c.jpg"), order(SortOrder.NEWEST))
    assertEquals(listOf("A.jpg", "b.jpg", "c.jpg"), order(SortOrder.BEST))
    assertEquals(listOf("c.jpg", "b.jpg", "A.jpg"), order(SortOrder.OLDEST))
    assertEquals(listOf("b.jpg", "c.jpg", "A.jpg"), order(SortOrder.LARGEST))
    assertEquals(listOf("A.jpg", "b.jpg", "c.jpg"), order(SortOrder.NAME))
    assertEquals(listOf("b.jpg", "c.jpg"), order(SortOrder.NEWEST, DateRange(t0 + 1 * day, t0 + 3 * day)))
  }

  @Test fun browsingUsesTheCaptureDateForOrderAndRange() {
    store.upsertFromCrawl(listOf(record(1, "scanned.jpg", t0 + 500 * day, taken = t0), record(2, "fresh.jpg", t0 + 10 * day)))
    assertEquals(listOf("fresh.jpg", "scanned.jpg"), store.recent(FileKind.entries.toSet(), 10).map { it.name })
    assertEquals(listOf("scanned.jpg"), store.recent(FileKind.entries.toSet(), 10, dates = DateRange(t0, t0 + day)).map { it.name })
  }

  @Test fun idsInDateRange() {
    store.upsertFromCrawl(listOf(record(1, "a.jpg", t0), record(2, "b.jpg", t0 + 5 * day)))
    val ids = store.knownMediaStoreIds("external_primary")
    assertEquals(setOf(ids.getValue(2)), store.idsInDateRange(DateRange(t0 + day, null)))
    assertTrue(store.idsInDateRange(DateRange(t0 + 50 * day, null)).isEmpty())
  }

  @Test fun migrationFillsTheDateFromTheModifiedTimeAndForcesAResync() {
    // exercised end to end by SchemaMigrationTest (every older layout upgrades to the current one); here the data effect
    val migration = Schema.MIGRATIONS.single { step -> step.any { "date_ms" in it } }.joinToString(";")
    assertTrue(migration.contains("UPDATE files SET date_ms = mtime"))
    assertTrue(migration.contains("ms_generation"))
  }
}
