package trawl.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WmClassTest {
  class Holder { companion object { @JvmField var awtAppClassName: String? = null } }

  private val field = { Holder::class.java.getDeclaredField("awtAppClassName") }

  @Test fun theWindowClassIsNamedAfterTheDebsLauncherEntryOnLinuxOnly() {
    Holder.awtAppClassName = null
    assertFalse(WmClass.apply(Os.WINDOWS, field, startToolkit = {}))
    assertFalse(WmClass.apply(Os.MAC, field, startToolkit = {}))
    assertNull(Holder.awtAppClassName)
    assertTrue(WmClass.apply(Os.LINUX, field, startToolkit = { Holder.awtAppClassName = "set by the toolkit constructor" }))
    assertEquals("trawl-Trawl", Holder.awtAppClassName)
  }

  @Test fun withoutTheToolkitOrTheOpenedPackageItIsAQuietNo() {
    assertFalse(WmClass.apply(Os.LINUX, { null }, startToolkit = {}))
    assertFalse(WmClass.apply(Os.LINUX, { throw IllegalStateException("not open") }, startToolkit = {}))
    assertFalse(WmClass.apply(Os.LINUX, { Holder::class.java.getDeclaredField("nope") }, startToolkit = {}))
  }
}
