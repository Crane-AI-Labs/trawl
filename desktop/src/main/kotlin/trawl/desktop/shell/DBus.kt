package trawl.desktop.shell

import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/*
 * A small D-Bus client, written for one job: talking to xdg-desktop-portal (the Wayland global shortcut) without a native library or a
 * dependency. It speaks the wire format from the D-Bus specification (little-endian output, either endianness accepted on input), the
 * EXTERNAL authentication over a Unix socket, and covers the types the portal uses: y b i u x t s o g v a () {}.
 * Not here, on purpose: file descriptors, introspection, name ownership beyond what a client needs, abstract-socket addresses (the JDK
 * cannot open them; the session bus on every desktop that has the portal is a path socket).
 */

/** A variant: a value together with its signature, as D-Bus carries it inside `a{sv}`. */
data class Variant(val sig: String, val value: Any?) {
  companion object {
    fun s(v: String) = Variant("s", v)
    fun u(v: Long) = Variant("u", v)
    fun b(v: Boolean) = Variant("b", v)
  }
}

/** A D-Bus error reply (or a local failure that looks like one). */
class DBusError(val name: String, message: String) : IOException("$name: $message")

/** Wire-format writer. Values are plain Kotlin: Int/Long for numbers, Boolean, String (for s, o and g), [Variant], List (arrays and structs), Map (a{..}). */
class WireWriter {
  private var buf = ByteArray(256)
  var size = 0
    private set

  private fun ensure(n: Int) { if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n)) }
  fun byte(v: Int) { ensure(1); buf[size++] = v.toByte() }
  fun align(n: Int) { while (size % n != 0) byte(0) }
  fun u32(v: Long) { align(4); for (i in 0 until 4) byte(((v shr (8 * i)) and 0xFF).toInt()) }
  fun u64(v: Long) { align(8); for (i in 0 until 8) byte(((v shr (8 * i)) and 0xFF).toInt()) }
  private fun patchU32(at: Int, v: Long) { for (i in 0 until 4) buf[at + i] = ((v shr (8 * i)) and 0xFF).toByte() }
  fun bytes(b: ByteArray) { ensure(b.size); System.arraycopy(b, 0, buf, size, b.size); size += b.size }
  fun toByteArray(): ByteArray = buf.copyOf(size)

  /** Writes every value of [sig] (a sequence of complete types) from [vals]. */
  fun values(sig: String, vals: List<Any?>) {
    var i = 0; var n = 0
    while (i < sig.length) {
      val end = WireTypes.end(sig, i)
      require(n < vals.size) { "signature $sig needs more values than the ${vals.size} given" }
      value(sig.substring(i, end), vals[n++])
      i = end
    }
    require(n == vals.size) { "signature $sig takes $n values, ${vals.size} given" }
  }

  fun value(sig: String, v: Any?) {
    when (val c = sig[0]) {
      'y' -> byte((v as Number).toInt())
      'b' -> u32(if (v as Boolean) 1 else 0)
      'i', 'u' -> u32((v as Number).toLong())
      'x', 't' -> u64((v as Number).toLong())
      's', 'o' -> { val b = (v as String).toByteArray(Charsets.UTF_8); u32(b.size.toLong()); bytes(b); byte(0) }
      'g' -> { val b = (v as String).toByteArray(Charsets.UTF_8); byte(b.size); bytes(b); byte(0) }
      'v' -> { val x = v as Variant; value("g", x.sig); value(x.sig, x.value) }
      'a' -> {
        val elem = sig.substring(1)
        u32(0)
        val lenAt = size - 4
        align(WireTypes.align(elem[0]))
        val start = size
        if (elem[0] == '{') {
          val kt = elem.substring(1, WireTypes.end(elem, 1)); val vt = elem.substring(1 + kt.length, elem.length - 1)
          for ((k, x) in v as Map<*, *>) { align(8); value(kt, k); value(vt, x) }
        } else {
          for (x in v as List<*>) value(elem, x)
        }
        patchU32(lenAt, (size - start).toLong())
      }
      '(' -> {
        align(8)
        val vals = v as List<*>
        var i = 1; var n = 0
        while (sig[i] != ')') { val e = WireTypes.end(sig, i); value(sig.substring(i, e), vals[n++]); i = e }
        require(n == vals.size) { "struct $sig takes $n members, ${vals.size} given" }
      }
      else -> error("unsupported type $c")
    }
  }
}

/** Wire-format reader over [data] from [start]; offsets are relative to the start of the message, so [start] must be 0 or 8-aligned. */
class WireReader(private val data: ByteArray, start: Int = 0, private val little: Boolean = true) {
  var pos = start
    private set

  fun align(n: Int) { while (pos % n != 0) pos++ }
  fun byte(): Int = data[pos++].toInt() and 0xFF
  fun u32(): Long { align(4); return num(4) }
  fun u64(): Long { align(8); return num(8) }
  private fun num(n: Int): Long {
    var r = 0L
    for (i in 0 until n) { val b = data[pos + i].toLong() and 0xFF; r = if (little) r or (b shl (8 * i)) else (r shl 8) or b }
    pos += n
    return r
  }

  fun values(sig: String): List<Any?> {
    val out = ArrayList<Any?>()
    var i = 0
    while (i < sig.length) { val e = WireTypes.end(sig, i); out += value(sig.substring(i, e)); i = e }
    return out
  }

  fun value(sig: String): Any? = when (val c = sig[0]) {
    'y' -> byte()
    'b' -> u32() != 0L
    'i' -> u32().toInt()
    'u' -> u32()
    'x', 't' -> u64()
    's', 'o' -> { val n = u32().toInt(); val s = String(data, pos, n, Charsets.UTF_8); pos += n + 1; s }
    'g' -> { val n = byte(); val s = String(data, pos, n, Charsets.UTF_8); pos += n + 1; s }
    'v' -> { val s = value("g") as String; Variant(s, value(s)) }
    'a' -> {
      val elem = sig.substring(1)
      val n = u32().toInt()
      align(WireTypes.align(elem[0]))
      val end = pos + n
      if (elem[0] == '{') {
        val kt = elem.substring(1, WireTypes.end(elem, 1)); val vt = elem.substring(1 + kt.length, elem.length - 1)
        val m = LinkedHashMap<Any?, Any?>()
        while (pos < end) { align(8); val k = value(kt); m[k] = value(vt) }
        m
      } else {
        val l = ArrayList<Any?>()
        while (pos < end) l += value(elem)
        l
      }
    }
    '(' -> {
      align(8)
      val l = ArrayList<Any?>()
      var i = 1
      while (sig[i] != ')') { val e = WireTypes.end(sig, i); l += value(sig.substring(i, e)); i = e }
      l
    }
    else -> error("unsupported type $c")
  }
}

object WireTypes {
  fun align(c: Char): Int = when (c) {
    'y', 'g', 'v' -> 1
    'b', 'i', 'u', 's', 'o', 'a' -> 4
    'x', 't', '(', '{' -> 8
    else -> error("unsupported type $c")
  }

  /** The index just past the one complete type that starts at [i]. */
  fun end(sig: String, i: Int): Int = when (sig[i]) {
    'a' -> end(sig, i + 1)
    '(', '{' -> {
      val close = if (sig[i] == '(') ')' else '}'
      var j = i + 1
      while (sig[j] != close) j = end(sig, j)
      j + 1
    }
    else -> i + 1
  }
}

/** One D-Bus message, decoded or about to be encoded. */
class DBusMessage(
  val type: Int,
  val serial: Long,
  val flags: Int = 0,
  val path: String? = null,
  val iface: String? = null,
  val member: String? = null,
  val errorName: String? = null,
  val replySerial: Long? = null,
  val destination: String? = null,
  val sender: String? = null,
  val signature: String = "",
  val body: List<Any?> = emptyList(),
) {
  companion object {
    const val METHOD_CALL = 1; const val METHOD_RETURN = 2; const val ERROR = 3; const val SIGNAL = 4
    const val NO_REPLY_EXPECTED = 1

    fun encode(m: DBusMessage): ByteArray {
      val body = WireWriter().also { it.values(m.signature, m.body) }.toByteArray()
      val h = WireWriter()
      h.byte('l'.code); h.byte(m.type); h.byte(m.flags); h.byte(1)
      h.u32(body.size.toLong()); h.u32(m.serial)
      // The header fields: array of (byte code, variant).
      val fields = ArrayList<List<Any?>>()
      m.path?.let { fields += listOf(1, Variant("o", it)) }
      m.iface?.let { fields += listOf(2, Variant("s", it)) }
      m.member?.let { fields += listOf(3, Variant("s", it)) }
      m.errorName?.let { fields += listOf(4, Variant("s", it)) }
      m.replySerial?.let { fields += listOf(5, Variant("u", it)) }
      m.destination?.let { fields += listOf(6, Variant("s", it)) }
      m.sender?.let { fields += listOf(7, Variant("s", it)) }
      if (m.signature.isNotEmpty()) fields += listOf(8, Variant("g", m.signature))
      h.value("a(yv)", fields)
      h.align(8)
      return h.toByteArray() + body
    }

    /** Decodes one whole message ([raw] = fixed header, fields and body, exactly as read from the socket). */
    fun decode(raw: ByteArray): DBusMessage {
      val little = raw[0] == 'l'.code.toByte()
      val r = WireReader(raw, 1, little)
      val type = r.byte(); val flags = r.byte(); r.byte()
      val bodyLen = r.u32().toInt(); val serial = r.u32()
      @Suppress("UNCHECKED_CAST") val fields = r.value("a(yv)") as List<List<Any?>>
      var path: String? = null; var iface: String? = null; var member: String? = null; var err: String? = null
      var reply: Long? = null; var dest: String? = null; var sender: String? = null; var sig = ""
      for (f in fields) {
        val v = (f[1] as Variant).value
        when (f[0] as Int) {
          1 -> path = v as String; 2 -> iface = v as String; 3 -> member = v as String; 4 -> err = v as String
          5 -> reply = v as Long; 6 -> dest = v as String; 7 -> sender = v as String; 8 -> sig = v as String
        }
      }
      r.align(8)
      val body = if (bodyLen == 0 || sig.isEmpty()) emptyList() else WireReader(raw, r.pos, little).values(sig)
      return DBusMessage(type, serial, flags, path, iface, member, err, reply, dest, sender, sig, body)
    }
  }
}

/**
 * A connection to a message bus: authenticated, greeted (`Hello`), with a reader thread that hands replies to the call that is waiting
 * and signals and incoming method calls to the handlers. Thread-safe for concurrent [call]s.
 */
class DBusConnection private constructor(private val ch: SocketChannel) : AutoCloseable {
  @Volatile var uniqueName: String = ""
    private set
  private val serial = java.util.concurrent.atomic.AtomicLong(1)
  private val pending = ConcurrentHashMap<Long, CompletableFuture<DBusMessage>>()
  private val writeLock = Any()
  private val signalHandlers = CopyOnWriteArrayList<(DBusMessage) -> Unit>()
  @Volatile private var closed = false

  /** Answers method calls made to this connection (a service, or a test's fake portal): return the reply body with its signature, or throw [DBusError]. */
  @Volatile var onMethodCall: ((DBusMessage) -> Pair<String, List<Any?>>)? = null

  fun onSignal(h: (DBusMessage) -> Unit) { signalHandlers += h }

  private fun readFully(buf: ByteArray, off: Int, len: Int) {
    val bb = ByteBuffer.wrap(buf, off, len)
    while (bb.hasRemaining()) if (ch.read(bb) < 0) throw IOException("the bus closed the connection")
  }

  private fun readMessage(): DBusMessage {
    val head = ByteArray(16)
    readFully(head, 0, 16)
    val little = head[0] == 'l'.code.toByte()
    fun u32(at: Int): Long { var r = 0L; for (i in 0 until 4) { val b = head[at + i].toLong() and 0xFF; r = if (little) r or (b shl (8 * i)) else (r shl 8) or b }; return r }
    val bodyLen = u32(4); val fieldsLen = u32(12)
    val padded = (fieldsLen + 7) / 8 * 8
    val total = 16 + padded + bodyLen
    if (total > MAX_MESSAGE) throw IOException("a D-Bus message of $total bytes is refused")
    val raw = head.copyOf(total.toInt())
    readFully(raw, 16, (total - 16).toInt())
    return DBusMessage.decode(raw)
  }

  private fun send(m: DBusMessage) {
    val bytes = DBusMessage.encode(m)
    synchronized(writeLock) {
      val bb = ByteBuffer.wrap(bytes)
      while (bb.hasRemaining()) ch.write(bb)
    }
  }

  private fun startReader() {
    Thread({
      try {
        while (!closed) dispatch(readMessage())
      } catch (e: Exception) {
        // closed or broken: fail whoever is waiting
      } finally {
        closed = true
        val err = IOException("the D-Bus connection closed")
        pending.values.forEach { it.completeExceptionally(err) }
        pending.clear()
      }
    }, "trawl-dbus").apply { isDaemon = true }.start()
  }

  private fun dispatch(m: DBusMessage) {
    when (m.type) {
      DBusMessage.METHOD_RETURN, DBusMessage.ERROR -> m.replySerial?.let { pending.remove(it) }?.complete(m)
      DBusMessage.SIGNAL -> for (h in signalHandlers) runCatching { h(m) }
      DBusMessage.METHOD_CALL -> {
        val h = onMethodCall
        val reply = try {
          if (h == null) throw DBusError("org.freedesktop.DBus.Error.UnknownMethod", "no handler")
          val (sig, body) = h(m)
          DBusMessage(DBusMessage.METHOD_RETURN, serial.getAndIncrement(), replySerial = m.serial, destination = m.sender, signature = sig, body = body)
        } catch (e: DBusError) {
          DBusMessage(DBusMessage.ERROR, serial.getAndIncrement(), errorName = e.name, replySerial = m.serial, destination = m.sender, signature = "s", body = listOf(e.message ?: ""))
        }
        if (m.flags and DBusMessage.NO_REPLY_EXPECTED == 0) runCatching { send(reply) }
      }
    }
  }

  /** Calls a method and waits for its reply; the reply body, or a [DBusError] for an error reply. */
  fun call(
    dest: String?, path: String, iface: String, member: String, sig: String = "", args: List<Any?> = emptyList(), timeoutMs: Long = 5_000,
  ): List<Any?> {
    if (closed) throw IOException("the D-Bus connection is closed")
    val s = serial.getAndIncrement()
    val f = CompletableFuture<DBusMessage>()
    pending[s] = f
    try {
      send(DBusMessage(DBusMessage.METHOD_CALL, s, path = path, iface = iface, member = member, destination = dest, signature = sig, body = args))
      val r = try { f.get(timeoutMs, TimeUnit.MILLISECONDS) } catch (e: TimeoutException) { throw DBusError("trawl.Timeout", "no answer to $iface.$member in ${timeoutMs / 1000} s") }
        catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? IOException) ?: IOException(e.cause) }
      if (r.type == DBusMessage.ERROR) throw DBusError(r.errorName ?: "unknown", (r.body.firstOrNull() as? String) ?: "")
      return r.body
    } finally { pending.remove(s) }
  }

  fun addMatch(rule: String) {
    call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "AddMatch", "s", listOf(rule))
  }

  /** Claims a well-known name (a service, or a test's fake portal). */
  fun requestName(name: String) {
    call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "RequestName", "su", listOf(name, 0L))
  }

  fun emitSignal(path: String, iface: String, member: String, sig: String, args: List<Any?>, destination: String? = null) {
    send(DBusMessage(DBusMessage.SIGNAL, serial.getAndIncrement(), path = path, iface = iface, member = member, destination = destination, signature = sig, body = args))
  }

  val isOpen: Boolean get() = !closed

  override fun close() { closed = true; runCatching { ch.close() } }

  companion object {
    private const val MAX_MESSAGE = 16L * 1024 * 1024

    /** The session bus address: `DBUS_SESSION_BUS_ADDRESS`, else the per-user socket systemd and every modern desktop create. Null when there is none. */
    fun sessionAddress(env: Map<String, String> = System.getenv(), uid: Long = currentUid()): String? {
      env["DBUS_SESSION_BUS_ADDRESS"]?.takeIf { it.isNotBlank() }?.let { return it }
      val run = env["XDG_RUNTIME_DIR"]?.takeIf { it.isNotBlank() } ?: "/run/user/$uid"
      return File(run, "bus").takeIf { it.exists() }?.let { "unix:path=${it.path}" }
    }

    /** The first usable `unix:path=` entry of a (possibly `;`-separated) address list, or null (abstract sockets cannot be opened from the JDK). */
    fun socketPath(address: String): String? =
      address.split(';').asSequence().map { it.trim() }.filter { it.startsWith("unix:") }
        .flatMap { it.removePrefix("unix:").split(',').asSequence() }.firstOrNull { it.startsWith("path=") }
        ?.removePrefix("path=")?.let(::unescape)

    private fun unescape(s: String): String {
      val sb = StringBuilder(); var i = 0
      while (i < s.length) {
        if (s[i] == '%' && i + 2 < s.length) { sb.append(s.substring(i + 1, i + 3).toInt(16).toChar()); i += 3 } else { sb.append(s[i]); i++ }
      }
      return sb.toString()
    }

    fun currentUid(): Long = runCatching {
      File("/proc/self/status").readLines().first { it.startsWith("Uid:") }.split(Regex("\\s+"))[1].toLong()
    }.getOrElse {
      runCatching { ProcessBuilder("id", "-u").start().inputStream.bufferedReader().readText().trim().toLong() }.getOrDefault(-1L)
    }

    /** Connects to the bus at [address], authenticates as this user (EXTERNAL) and says Hello. Throws [IOException] on any failure. */
    fun connect(address: String, uid: Long = currentUid()): DBusConnection {
      val path = socketPath(address) ?: throw IOException("this bus address cannot be opened here: $address")
      val ch = SocketChannel.open(StandardProtocolFamily.UNIX)
      try {
        ch.connect(UnixDomainSocketAddress.of(path))
        // SASL: a NUL byte, then AUTH EXTERNAL with the user id written as decimal digits, hex-encoded.
        val hex = uid.toString().toByteArray().joinToString("") { "%02x".format(it) }
        ch.write(ByteBuffer.wrap(byteArrayOf(0) + "AUTH EXTERNAL $hex\r\n".toByteArray()))
        val line = readLine(ch)
        if (!line.startsWith("OK")) throw IOException("the bus refused the login: $line")
        ch.write(ByteBuffer.wrap("BEGIN\r\n".toByteArray()))
      } catch (e: Exception) { runCatching { ch.close() }; throw e as? IOException ?: IOException(e) }
      val c = DBusConnection(ch)
      c.startReader()
      try {
        c.uniqueName = c.call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "Hello")[0] as String
      } catch (e: Exception) { c.close(); throw e as? IOException ?: IOException(e) }
      return c
    }

    private fun readLine(ch: SocketChannel): String {
      val sb = StringBuilder(); val one = ByteBuffer.allocate(1)
      while (sb.length < 512) {
        one.clear()
        if (ch.read(one) < 0) throw IOException("the bus closed during login")
        val c = one.get(0).toInt().toChar()
        if (c == '\n') break
        sb.append(c)
      }
      return sb.toString().trimEnd('\r')
    }
  }
}
