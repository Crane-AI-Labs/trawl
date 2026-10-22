package trawl.desktop.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Byte-level checks of the D-Bus marshalling against values worked out by hand from the specification's alignment and padding rules. */
class DBusWireTest {
  private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it) }
  private fun bytes(h: String) = h.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
  private fun write(sig: String, v: Any?) = hex(WireWriter().also { it.value(sig, v) }.toByteArray())

  @Test fun stringsAreLengthPrefixedAndNulTerminated() {
    assertEquals("02 00 00 00 68 69 00", write("s", "hi"))
    assertEquals("02 00 00 00 68 69 00", write("o", "hi"))
    assertEquals("02 68 69 00", write("g", "hi"))
  }

  @Test fun numbersAreLittleEndianAndAligned() {
    assertEquals("07 00 00 00", write("u", 7L))
    assertEquals("ff ff ff ff", write("i", -1))
    assertEquals("01 00 00 00", write("b", true))
    val w = WireWriter().also { it.byte(9); it.value("x", 0x0102L) }
    assertEquals("09 00 00 00 00 00 00 00 02 01 00 00 00 00 00 00", hex(w.toByteArray()))
    val u = WireWriter().also { it.byte(9); it.value("u", 5L) }
    assertEquals("09 00 00 00 05 00 00 00", hex(u.toByteArray()))
  }

  @Test fun aVariantCarriesItsSignatureThenTheValue() {
    assertEquals("01 73 00 00 01 00 00 00 61 00", write("v", Variant.s("a")))
    assertEquals("01 75 00 00 2a 00 00 00", write("v", Variant.u(42)))
  }

  @Test fun aStructStartsOnAnEightByteBoundary() {
    val w = WireWriter().also { it.byte(1); it.value("(su)", listOf("a", 5L)) }
    assertEquals("01 00 00 00 00 00 00 00 01 00 00 00 61 00 00 00 05 00 00 00", hex(w.toByteArray()))
  }

  @Test fun aDictionaryOfVariantsPadsItsEntriesToEight() {
    // length 0x12, four bytes of padding to reach the first (8-aligned) entry, then key, variant, and the variant's string aligned to 4
    assertEquals(
      "12 00 00 00 00 00 00 00 01 00 00 00 6b 00 01 73 00 00 00 00 01 00 00 00 76 00",
      write("a{sv}", mapOf("k" to Variant.s("v"))),
    )
  }

  @Test fun anEmptyDictionaryStillPadsToItsElementAlignment() {
    assertEquals("00 00 00 00 00 00 00 00", write("a{sv}", emptyMap<String, Variant>()))
  }

  @Test fun anArrayOfStructsAndTheBindShortcutsBody() {
    val body = listOf(
      "/org/freedesktop/portal/desktop/session/1_2/t",
      listOf(listOf("quick", mapOf("description" to Variant.s("d"), "preferred_trigger" to Variant.s("CTRL+space")))),
      "",
      mapOf("handle_token" to Variant.s("t")),
    )
    val sig = "oa(sa{sv})sa{sv}"
    val raw = WireWriter().also { it.values(sig, body) }.toByteArray()
    assertEquals(body, WireReader(raw).values(sig))
  }

  @Test fun theReaderReversesTheWriterForEveryType() {
    val sig = "ybiuxtsogvaya{sv}(is)"
    val vals = listOf(7, true, -3, 4000000000L, -5000000000L, 1L shl 40, "héllo", "/a/b", "ay", Variant.u(9), listOf(1, 2, 3), mapOf("a" to Variant.b(true)), listOf(-1, "x"))
    val raw = WireWriter().also { it.values(sig, vals) }.toByteArray()
    val back = WireReader(raw).values(sig)
    assertEquals(vals, back)
  }

  @Test fun aSignatureSplitsIntoCompleteTypes() {
    assertEquals(1, WireTypes.end("u", 0))
    assertEquals(5, WireTypes.end("a{sv}x", 0))
    assertEquals(9, WireTypes.end("a(sa{sv})s", 0))
  }

  // ---- whole messages ----

  private val minimalCall = "6c 01 00 01 00 00 00 00 01 00 00 00 1a 00 00 00 " +
    "01 01 6f 00 02 00 00 00 2f 61 00 00 00 00 00 00 " +
    "03 01 73 00 01 00 00 00 4d 00 00 00 00 00 00 00"

  @Test fun aBareMethodCallEncodesToTheExpectedBytes() {
    val m = DBusMessage(DBusMessage.METHOD_CALL, 1, path = "/a", member = "M")
    assertEquals(minimalCall.replace("  ", " "), hex(DBusMessage.encode(m)))
  }

  @Test fun aLittleEndianMessageDecodes() {
    val m = DBusMessage.decode(bytes(minimalCall))
    assertEquals(DBusMessage.METHOD_CALL, m.type)
    assertEquals(1L, m.serial)
    assertEquals("/a", m.path)
    assertEquals("M", m.member)
    assertNull(m.iface)
    assertTrue(m.body.isEmpty())
  }

  @Test fun aBigEndianMessageDecodes() {
    val big = "42 01 00 01 00 00 00 00 00 00 00 01 00 00 00 1a " +
      "01 01 6f 00 00 00 00 02 2f 61 00 00 00 00 00 00 " +
      "03 01 73 00 00 00 00 01 4d 00 00 00 00 00 00 00"
    val m = DBusMessage.decode(bytes(big))
    assertEquals(1L, m.serial)
    assertEquals("/a", m.path)
    assertEquals("M", m.member)
  }

  @Test fun theBodyStartsOnAnEightByteBoundaryAfterTheHeader() {
    val m = DBusMessage(DBusMessage.METHOD_CALL, 2, path = "/a", iface = "a.B", member = "M", destination = "a.C", signature = "u", body = listOf(7L))
    val raw = DBusMessage.encode(m)
    assertEquals(0, (raw.size - 4) % 8)
    assertEquals("07 00 00 00", hex(raw.copyOfRange(raw.size - 4, raw.size)))
    val bodyLen = (raw[4].toInt() and 0xFF)
    assertEquals(4, bodyLen)
  }

  @Test fun aSignalWithAPortalResponseBodyRoundTrips() {
    val body = listOf(0L, mapOf("session_handle" to Variant.s("/org/freedesktop/portal/desktop/session/1_9/x"), "shortcuts" to Variant("a(sa{sv})", listOf(listOf("quick", mapOf("trigger_description" to Variant.s("Press Ctrl+Space")))))))
    val m = DBusMessage(DBusMessage.SIGNAL, 12, path = "/org/freedesktop/portal/desktop/request/1_9/t", iface = "org.freedesktop.portal.Request", member = "Response", sender = ":1.3", signature = "ua{sv}", body = body)
    val d = DBusMessage.decode(DBusMessage.encode(m))
    assertEquals(DBusMessage.SIGNAL, d.type)
    assertEquals("Response", d.member)
    assertEquals(":1.3", d.sender)
    assertEquals("ua{sv}", d.signature)
    assertEquals(0L, d.body[0])
    val results = d.body[1] as Map<*, *>
    assertEquals(Variant.s("/org/freedesktop/portal/desktop/session/1_9/x"), results["session_handle"])
    assertEquals(body[1], results)
  }

  @Test fun anErrorReplyKeepsItsNameAndReplySerial() {
    val m = DBusMessage(DBusMessage.ERROR, 5, errorName = "org.freedesktop.DBus.Error.UnknownInterface", replySerial = 3, destination = ":1.7", signature = "s", body = listOf("no such interface"))
    val d = DBusMessage.decode(DBusMessage.encode(m))
    assertEquals("org.freedesktop.DBus.Error.UnknownInterface", d.errorName)
    assertEquals(3L, d.replySerial)
    assertEquals(listOf<Any?>("no such interface"), d.body)
  }

  // ---- addresses ----

  @Test fun socketPathReadsAPathAddress() {
    assertEquals("/run/user/1000/bus", DBusConnection.socketPath("unix:path=/run/user/1000/bus"))
    assertEquals("/x/bus", DBusConnection.socketPath("unix:path=/x/bus,guid=abc"))
    assertEquals("/a b/bus", DBusConnection.socketPath("unix:path=/a%20b/bus"))
  }

  @Test fun socketPathRefusesAbstractSocketsAndTakesTheFirstUsableOne() {
    assertNull(DBusConnection.socketPath("unix:abstract=/tmp/dbus-x,guid=1"))
    assertEquals("/y/bus", DBusConnection.socketPath("unix:abstract=/tmp/dbus-x;unix:path=/y/bus"))
    assertNull(DBusConnection.socketPath("tcp:host=localhost,port=1"))
  }

  @Test fun theSessionAddressPrefersTheEnvironment() {
    assertEquals("unix:path=/q/bus", DBusConnection.sessionAddress(mapOf("DBUS_SESSION_BUS_ADDRESS" to "unix:path=/q/bus"), 1000))
    assertNull(DBusConnection.sessionAddress(mapOf("XDG_RUNTIME_DIR" to "/definitely/not/here"), 1000))
  }
}
