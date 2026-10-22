package com.craneai.findr.engine

/**
 * The user's Pause for indexing, as a rule: what the stored flag becomes after each event. Only the user's Resume clears
 * it. An app launch or a retry (START) keeps it: FindrEngine.startIndexing() used to clear it, so a paused index started
 * again the next time the app opened.
 */
object IndexPause {
  enum class Event { START, PAUSE, RESUME }

  fun after(event: Event, paused: Boolean): Boolean = when (event) {
    Event.START -> paused
    Event.PAUSE -> true
    Event.RESUME -> false
  }
}
