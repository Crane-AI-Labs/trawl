package com.craneai.findr.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeaningBudgetTest {
  private fun doc(n: Int) = List(n) { "chunk number $it of a long report about many things" }

  @Test fun aDocumentThatFitsIsEmbeddedWhole() {
    assertTrue(MeaningBudget.chosen(doc(MeaningBudget.CHUNKS)).all { it })
    assertTrue(MeaningBudget.chosen(doc(3)).all { it })
    assertEquals(0, MeaningBudget.chosen(emptyList()).size)
  }

  @Test fun aLongDocumentKeepsItsOpeningAndASpreadOfTheRest() {
    val c = MeaningBudget.chosen(doc(7_246))
    assertEquals(MeaningBudget.CHUNKS, c.count { it })
    assertTrue("the opening", (0 until MeaningBudget.OPENING).all { c[it] })
    val picked = c.withIndex().filter { it.value }.map { it.index }
    assertTrue("the spread reaches the end", picked.last() > 7_000)
    val gaps = picked.drop(MeaningBudget.OPENING).zipWithNext { a, b -> b - a }
    assertTrue("an even spread: $gaps", gaps.max() - gaps.min() <= 1)
  }

  @Test fun theChunksThatHoldHeadingsAreEmbedded() {
    val chunks = doc(300).toMutableList()
    chunks[150] = "and so on. Methods and Results The samples were taken"
    chunks[251] = "the end of part one. Budget for 2027 The plan costs"
    val c = MeaningBudget.chosen(chunks, listOf("Methods and Results", "Budget for 2027", "A Heading Nowhere"), budget = 60)
    assertEquals(60, c.count { it })
    assertTrue(c[150]); assertTrue(c[251])
  }

  @Test fun everyCountGetsExactlyTheBudget() {
    for (budget in listOf(1, 8, 13, 60)) for (total in budget + 1..budget * 4 + 3) {
      val headings = List(30) { "h$it" }
      val chunks = List(total) { i -> if (i % 7 == 0) "x h${i / 7} y" else "plain $i" }
      assertEquals("total $total budget $budget", budget, MeaningBudget.chosen(chunks, headings, budget).count { it })
    }
  }

  @Test fun headingsAreFoundInMarkdownNumberedAndTitleLines() {
    val page = """
      # Trawl release notes
      Some prose that is a full sentence and is not a heading.
      2.3 Results on the A06
      IV. Budget
      Field Notes From Gulu
      this line starts lower case so it is prose
      A Sentence That Ends With A Full Stop.
      ## Next steps ##
    """.trimIndent()
    assertEquals(listOf("Trawl release notes", "2.3 Results on the A06", "IV. Budget", "Field Notes From Gulu", "Next steps"), MeaningBudget.headings(listOf(page)))
    assertFalse(MeaningBudget.headings(listOf("Just one long sentence of ordinary prose, with commas, that goes on.")).isNotEmpty())
  }
}
