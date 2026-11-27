package com.craneai.findr.engine

import com.craneai.findr.engine.TextClass.Kind.HUMAN
import com.craneai.findr.engine.TextClass.Kind.MACHINE
import org.junit.Assert.assertEquals
import org.junit.Test

class TextClassTest {
  private fun big(s: String, bytes: Int = 20_000) = buildString { while (length < bytes) append(s).append('\n') }
  private fun kind(text: String, table: Boolean = false) = TextClass.of(text, text.length.toLong(), table).kind

  @Test fun prosePeopleWriteIsHuman() {
    assertEquals(HUMAN, kind(big("We met the head teacher on Tuesday and agreed the school fees for the second term, with a discount for siblings.")))
    // Luganda prose: no English stop words, still words.
    assertEquals(HUMAN, kind(big("Abaana bagenda ku ssomero buli lunaku era basoma nnyo okusobola okutuuka ku buwanguzi mu bulamu bwabwe.")))
  }

  @Test fun aMoneyStatementIsHuman() {
    assertEquals(HUMAN, kind(big("04/07 Received from Kampala Poultry Feeds Ltd (egg delivery payment) UGX 380,000 Ref AM-7741-0390 Balance UGX 442,000")))
  }

  @Test fun aReadmeFullOfCommandsAndLinksIsHuman() {
    val readme = big("## Install\nRun the build with the wrapper, then open the app. See https://example.org/docs/getting-started/install-on-a-new-computer for details.\n" +
      "`./gradlew :desktop:run` starts it; the index lives in `/home/someone/.local/share/trawl/index/findr-index.db`.\nIt reads | tables | too |")
    assertEquals(HUMAN, kind(readme))
  }

  @Test fun aSmallTableIsAPersonsAndAHugeGeneratedOneIsNot() {
    val budget = "Item,Amount,Notes\nRent,500000,\"paid on the 1st\"\nSchool fees,1200000,\"term 2\"\nTransport,150000,boda\n"
    assertEquals(HUMAN, kind(big(budget, 200_000), table = true))
    val fixture = big("\"4f9a1c\",\"0.48213\",\"0.11872\",\"0.99102\",\"1794937083\",\"7741\",\"0.0031\"", 2_000_000)
    assertEquals(MACHINE, kind(fixture, table = true))
  }

  @Test fun codeLogsJsonAndBlobsAreMachineText() {
    assertEquals(MACHINE, kind(big("private fun load(store: IndexStore) { val quick = Modality.CLIP in holds; loadFrom({ store.vectorGeneration(quick) }) }")))
    assertEquals(MACHINE, kind(big("11-11 02:30:46.384 570 584 W system_server: Suspending all threads took: 6.336ms")))
    assertEquals(MACHINE, kind(big("{\"run\": 3, \"step\": 812, \"loss\": 0.41231, \"reward\": [0.1, -0.3], \"ok\": true},")))
    assertEquals(MACHINE, kind(big("QmFzZTY0IGVuY29kZWQgYmxvYiB0aGF0IGdvZXMgb24gYW5kIG9uIGFuZCBvbiBmb3IgYSB2ZXJ5IGxvbmcgdGltZQ==")))
  }

  @Test fun aResultsDumpWithProsePromptsIsMachineText() {
    // Benchmark output: every record carries a sentence of prose, but the keys give it away (large dev corpus: 324,743 json vectors).
    assertEquals(MACHINE, kind(big("{ \"run\": 3, \"step\": 812, \"model\": \"gemma\", \"loss\": 0.41, \"prompt\": \"how did the family spend the long evening at home\" },")))
  }

  @Test fun notesWrittenAsJsonArePeoplesText() {
    val notes = buildString {
      append("{ \"notes\": { \"summary_lines\": [")
      repeat(120) { i -> append("\"Lecture $i explains how verifiers choose the correct answer and guide the reasoning of a model during generation\", ") }
      append("], \"speakers\": [\"A\"], \"title\": \"Verification\" } }")
    }
    assertEquals(HUMAN, kind(notes))
  }

  @Test fun aSmallFileIsAlwaysAPersons() {
    assertEquals(HUMAN, TextClass.of("{\"a\":1,\"b\":[2,3]}", 18).kind)
  }
}
