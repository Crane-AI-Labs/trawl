package trawl.desktop.ui

import trawl.desktop.Phase
import trawl.desktop.IndexUi

/** What the bottom status bar and the meter card above the list each say, decided in one place so no reading text appears twice. Pure. */
object StatusParts {
  /** The card (with its detail panel) takes the place of the status line for every state that needs the person: a warning, a pause, an index that would not open. */
  fun cardShown(line: MeterLine, ix: IndexUi): Boolean = line.warning || ix.phase == Phase.PAUSED || ix.openProblem != null

  /** The status bar's reading text, or null when the card is up and already says it ("Names and words only" used to show in both). */
  fun summary(line: MeterLine, ix: IndexUi, cardShown: Boolean): String? {
    if (cardShown) return null
    return listOf(line.title, line.sub).filter { it.isNotEmpty() }.joinToString("  \u00b7  ")
  }
}
