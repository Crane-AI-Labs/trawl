package com.craneai.findr.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class CommonMimeTypeTest {
  @Test fun sameTypeIsKept() = assertEquals("image/jpeg", FileActions.commonMimeType(listOf("image/jpeg", "IMAGE/JPEG")))
  @Test fun sameFamilyIsWildcarded() = assertEquals("image/*", FileActions.commonMimeType(listOf("image/jpeg", "image/png")))
  @Test fun mixedFamiliesAreAny() = assertEquals("*/*", FileActions.commonMimeType(listOf("image/jpeg", "application/pdf")))
  @Test fun anUnknownTypeMakesItAny() = assertEquals("*/*", FileActions.commonMimeType(listOf("image/jpeg", null)))
  @Test fun junkTypesCountAsUnknown() = assertEquals("*/*", FileActions.commonMimeType(listOf("image/jpeg", "image/", "jpeg")))
  @Test fun emptyIsAny() = assertEquals("*/*", FileActions.commonMimeType(emptyList()))
}
