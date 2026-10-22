package com.craneai.findr.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** :core keeps the pack ids (it has no string resources); the catalog in :app must stay in step with them. */
class PackIdsTest {
  @Test fun theCatalogAndTheSharedIdsAgree() {
    assertEquals(PackIds.ALL, PackCatalog.all.map { it.id })
    assertEquals(PackIds.SPEECH, PackCatalog.speech.id)
    assertEquals(PackIds.QUICK_LOOK, PackCatalog.quickLook.id)
    assertEquals(PackIds.PRIVACY, PackCatalog.privacy.id)
  }
}
