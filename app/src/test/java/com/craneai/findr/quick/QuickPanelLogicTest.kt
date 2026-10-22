package com.craneai.findr.quick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickPanelLogicTest {
  @Test fun `tile is offered once to a new person whose search found something`() {
    assertTrue(TileOffer.shouldOffer(TileOfferState.NEW, sdk = 36, hits = 3, cameFromTile = false))
  }

  @Test fun `never offered after any answer`() {
    for (s in listOf(TileOfferState.OFFERED, TileOfferState.ADDED, TileOfferState.DECLINED)) {
      assertFalse("state $s", TileOffer.shouldOffer(s, sdk = 36, hits = 3, cameFromTile = false))
    }
  }

  @Test fun `not offered before there is a result, below Android 13, or to someone who came by the tile`() {
    assertFalse(TileOffer.shouldOffer(TileOfferState.NEW, sdk = 36, hits = 0, cameFromTile = false))
    assertFalse(TileOffer.shouldOffer(TileOfferState.NEW, sdk = 32, hits = 3, cameFromTile = false))
    assertFalse(TileOffer.shouldOffer(TileOfferState.NEW, sdk = 36, hits = 3, cameFromTile = true))
  }

  // Copy's path resolution (formerly QuickActions.pathFromIndex) now lives in, and is tested by,
  // com.craneai.findr.ui.common.FileActionsTest: the panel's Copy delegates to FileActions.clipboardUri.
}
