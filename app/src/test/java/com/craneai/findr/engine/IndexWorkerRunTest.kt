package com.craneai.findr.engine

import com.craneai.findr.core.PauseReason
import com.craneai.findr.diag.RunEnd
import org.junit.Assert.assertEquals
import org.junit.Test

class IndexWorkerRunTest {
  @Test fun theRequestTagNamesTheRun() {
    assertEquals("resume", IndexWorker.runKind(setOf(IndexWorker.NAME, IndexWorker.RESUME)))
    assertEquals("now", IndexWorker.runKind(setOf(IndexWorker.NAME, IndexWorker.NOW)))
    assertEquals("bulk", IndexWorker.runKind(setOf(IndexWorker.BULK)))
    assertEquals("charger", IndexWorker.runKind(setOf(IndexWorker.CHARGER)))
    assertEquals("later", IndexWorker.runKind(setOf(IndexWorker.LATER)))
    assertEquals("next", IndexWorker.runKind(setOf(IndexWorker.NEXT)))
    assertEquals("periodic", IndexWorker.runKind(setOf(IndexWorker.PERIODIC)))
    assertEquals("index", IndexWorker.runKind(setOf(IndexWorker.NAME)))
    assertEquals("index", IndexWorker.runKind(emptySet()))
  }

  @Test fun aHoldIsHeatOrBattery() {
    assertEquals(RunEnd.HELD_HEAT, IndexWorker.heldEnd(PauseReason.TOO_HOT))
    assertEquals(RunEnd.HELD_BATTERY, IndexWorker.heldEnd(PauseReason.BATTERY_LOW))
    assertEquals(RunEnd.HELD_BATTERY, IndexWorker.heldEnd(null))
  }

  @Test fun aServiceTypeIsAlwaysOnePureType() {
    val data = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    val media = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
    for (t in FgsType.entries) {
      val v = IndexWorker.serviceType(t)
      assert(v == data || v == media) { "not a pure type: $v" }
    }
    assertEquals(data, IndexWorker.serviceType(FgsType.DATA_SYNC))
  }
}
