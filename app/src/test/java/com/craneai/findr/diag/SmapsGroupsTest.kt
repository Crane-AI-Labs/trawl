package com.craneai.findr.diag

import org.junit.Assert.assertEquals
import org.junit.Test

class SmapsGroupsTest {
  private val sample = """
    7000000000-7000100000 rw-p 00000000 00:00 0                              [anon:scudo:primary]
    Size:               1024 kB
    Rss:                 900 kB
    Pss:                 900 kB
    Shared_Clean:          0 kB
    Shared_Dirty:          0 kB
    Private_Clean:         0 kB
    Private_Dirty:       900 kB
    Swap:                100 kB
    VmFlags: rd wr mr mw me ac
    7100000000-7100200000 r--s 00000000 fe:30 1234                           /data/user/0/com.craneai.findr/files/models/embeddinggemma-2-text-vision-440m.litertlm
    Size:               2048 kB
    Rss:                2048 kB
    Pss:                2048 kB
    Private_Clean:      2048 kB
    Private_Dirty:         0 kB
    7200000000-7200010000 r-xp 00000000 fe:30 99                             /data/app/x/lib/arm64/libonnxruntime.so
    Size:                 64 kB
    Rss:                  64 kB
    Pss:                  32 kB
    Shared_Clean:         64 kB
    Private_Clean:         0 kB
    Private_Dirty:         0 kB
    7300000000-7300010000 rw-p 00000000 00:00 0
    Size:                 64 kB
    Rss:                  64 kB
    Pss:                  64 kB
    Private_Dirty:        64 kB
  """.trimIndent()

  @Test fun foldsMappingsIntoGroupsWithDirtyAndCleanSeparate() {
    val r = SmapsGroups.parse(sample)
    val heap = r.groups.getValue("native.heap")
    assertEquals(900L, heap.privateDirtyKb); assertEquals(100L, heap.swapKb)
    val model = r.groups.getValue("model.litertlm")
    assertEquals(2048L, model.privateCleanKb); assertEquals(0L, model.privateDirtyKb)
    assertEquals(32L, r.groups.getValue("libs.so").pssKb)
    assertEquals(64L, r.groups.getValue("anon.unnamed").privateDirtyKb)
    assertEquals(900L + 2048 + 32 + 64, r.totalPssKb)
  }

  @Test fun classifiesTheKindsThatMatter() {
    assertEquals("model.onnx", SmapsGroups.classify("/data/user/0/p/files/models/tinyclip-image.onnx"))
    assertEquals("cache.xnnpack", SmapsGroups.classify("/data/user/0/p/cache/model.litertlm.xnnpack_cache"))
    assertEquals("java.heap", SmapsGroups.classify("[anon:dalvik-main space (region space)]"))
    assertEquals("graphics", SmapsGroups.classify("/dev/kgsl-3d0"))
    assertEquals("stacks", SmapsGroups.classify("[anon:stack_and_tls:1234]"))
  }
}
