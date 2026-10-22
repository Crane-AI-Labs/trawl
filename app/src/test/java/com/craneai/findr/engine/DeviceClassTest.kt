package com.craneai.findr.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceClassTest {
  private fun profile(ramMb: Long, bigMhz: Int, manufacturer: String, brand: String = manufacturer) = DeviceProfile(
    model = "x", socManufacturer = "", socModel = "", ramMb = ramMb, bigCores = 2, bigMaxMhz = bigMhz, littleCores = 6, littleMaxMhz = 1800,
    hasOpenCl = true, npuModelSuffix = null, manufacturer = manufacturer, brand = brand,
  )

  @Test fun theTestPhonesLandWhereTheStudyPutThem() {
    assertEquals(DeviceClass(RamClass.TIGHT, SocTier.ENTRY, Oem.TRANSSION), profile(3_744, 2_000, "TECNO").deviceClass)       // KN4 / KN3
    assertEquals(DeviceClass(RamClass.TIGHT, SocTier.ENTRY, Oem.SAMSUNG), profile(3_700, 2_000, "samsung").deviceClass)       // A06
    assertEquals(DeviceClass(RamClass.MID, SocTier.MID, Oem.TRANSSION), profile(7_900, 2_200, "INFINIX MOBILITY LIMITED", "Infinix").deviceClass)
    assertEquals(DeviceClass(RamClass.ROOMY, SocTier.HIGH, Oem.SAMSUNG), profile(11_300, 3_400, "samsung").deviceClass)       // S24 Ultra
  }

  @Test fun ramClassEdges() {
    assertEquals(RamClass.TIGHT, RamClass.of(MemoryBudget.TIGHT_TOTAL_MB))
    assertEquals(RamClass.MID, RamClass.of(MemoryBudget.TIGHT_TOTAL_MB + 1))
    assertEquals(RamClass.MID, RamClass.of(RamClass.MID_UP_TO_MB))
    assertEquals(RamClass.ROOMY, RamClass.of(RamClass.MID_UP_TO_MB + 1))
    assertEquals(RamClass.MID, RamClass.of(0))
  }

  @Test fun socTierEdges() {
    assertEquals(SocTier.ENTRY, SocTier.of(2_000))
    assertEquals(SocTier.MID, SocTier.of(2_200))
    assertEquals(SocTier.MID, SocTier.of(2_600))
    assertEquals(SocTier.HIGH, SocTier.of(3_150))
    assertEquals(SocTier.MID, SocTier.of(0))
  }

  @Test fun oemByManufacturerOrBrand() {
    assertEquals(Oem.TRANSSION, Oem.of("TRANSSION HOLDINGS", "TECNO"))
    assertEquals(Oem.TRANSSION, Oem.of("itel", null))
    assertEquals(Oem.XIAOMI, Oem.of("Xiaomi", "Redmi"))
    assertEquals(Oem.XIAOMI, Oem.of("Xiaomi", "POCO"))
    assertEquals(Oem.SAMSUNG, Oem.of("samsung", "samsung"))
    assertEquals(Oem.OTHER, Oem.of("Google", "google"))
    assertEquals(Oem.OTHER, Oem.of(null, null))
  }
}
