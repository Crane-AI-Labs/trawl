package com.craneai.findr.ui.fake

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.KindFilter
import com.craneai.findr.core.Locator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSearchTest {
  private val entries = FakeData.build(now = 1_700_000_000_000L)

  @Test fun stopWordsAreIgnored() {
    assertEquals(listOf("school", "fees"), FakeSearch.tokens("the school fees of my"))
  }

  @Test fun gibberishFindsNothing() {
    val (hits, strong) = FakeSearch.run(entries, "zzzxqv", KindFilter.ALL, 60, useMeaning = true)
    assertTrue(hits.isEmpty())
    assertEquals(0, strong)
  }

  @Test fun filterNarrowsKinds() {
    val (hits, _) = FakeSearch.run(entries, "school", KindFilter.DOCUMENTS, 60, useMeaning = true)
    assertTrue(hits.all { it.file.kind == FileKind.DOCUMENT || it.file.kind == FileKind.PDF })
  }

  @Test fun strongHitsComeFirst() {
    val (hits, strong) = FakeSearch.run(entries, "school fees", KindFilter.ALL, 60, useMeaning = true)
    assertTrue(hits.isNotEmpty())
    assertTrue(strong in 0..hits.size)
    // scores never rise past the fold except by strength
    val strongScores = hits.take(strong).map { it.score }
    assertEquals(strongScores.sortedDescending(), strongScores)
  }

  @Test fun everyUiStateIsReachable() {
    val queries = listOf("school fees", "wedding", "money", "sunset", "funny", "rent", "electricity", "malaria", "matooke")
    val all = queries.flatMap { FakeSearch.run(entries, it, KindFilter.ALL, 60, true).first }
    assertTrue("a page locator", all.any { it.locator is Locator.Page })
    assertTrue("a time locator", all.any { it.locator is Locator.Time })
    assertTrue("a highlighted snippet", all.any { it.snippet?.contains('«') == true })
  }

  @Test fun snippetMarkersAreBalanced() {
    val hits = listOf("school", "wedding", "momo", "rent").flatMap { FakeSearch.run(entries, it, KindFilter.ALL, 60, true).first }
    for (h in hits) {
      val s = h.snippet ?: continue
      assertEquals(s, s.count { it == '«' }, s.count { it == '»' })
    }
  }
}
