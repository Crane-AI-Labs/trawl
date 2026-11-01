package com.craneai.findr.ui.delivery

import org.junit.Assert.assertEquals
import org.junit.Test


class ModelsGetLabelTest {
  @Test fun `a known size is kept`() = assertEquals("Get the models (670 MB)", modelsGetLabel("Get the models (670 MB)", 670))
  @Test fun `zero hides the size`() = assertEquals("Get the models", modelsGetLabel("Get the models (0 MB)", 0))
  @Test fun `other languages too`() {
    assertEquals("Funa modeli", modelsGetLabel("Funa modeli (0 MB)", 0))
    assertEquals("تنزيل النماذج", modelsGetLabel("تنزيل النماذج (0 MB)", 0))
  }
}
