package trawl.desktop.shell

import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * One Trawl per data folder, and a second launch hands over to the first. (The only single-instance code: an earlier, unused copy in
 * `trawl.desktop.SingleInstance` was removed, so there is one lock and one hand-over to reason about.)
 *
 * Why this and not a TCP port: Trawl has no network code at all, and a loopback port is still a socket on the network stack. A Unix
 * domain socket is a file in the data folder, readable only by the person (0600 on a Unix-like OS; on Windows it lives under the
 * person's own profile). The lock is [FileChannel.tryLock] on `instance.lock`: the OS drops it when the process dies, however it dies,
 * so there is never a stale lock to clean up.
 *
 * Commands are one line: "show" (bring the window forward) and "quick" (open the quick panel).
 */
class SingleInstanceShell private constructor(
  private val lockChannel: FileChannel,
  private val lock: FileLock,
  private val server: ServerSocketChannel?,
  private val socketFile: File,
) : AutoCloseable {
  @Volatile private var closed = false

  /** Starts answering second launches. [onCommand] runs on a background thread. */
  fun listen(onCommand: (String) -> Unit) {
    val server = server ?: return
    Thread({
      while (!closed) {
        val ch = try { server.accept() } catch (_: IOException) { break }
        // One short-lived thread per caller, and a deadline on what it says: a caller that connects and goes quiet must not block the next.
        Thread({
          runCatching {
            ch.use {
              val line = readLine(it, WAIT_MS)
              if (line != null && line in COMMANDS) onCommand(line)
              it.write(ByteBuffer.wrap("ok\n".toByteArray()))
            }
          }
        }, "trawl-single-instance-caller").apply { isDaemon = true }.start()
      }
    }, "trawl-single-instance").apply { isDaemon = true }.start()
  }

  override fun close() {
    closed = true
    runCatching { server?.close() }
    runCatching { Files.deleteIfExists(socketFile.toPath()) }
    runCatching { lock.release() }
    runCatching { lockChannel.close() }
  }

  sealed interface Outcome {
    /** This is the only Trawl: run, and call [SingleInstanceShell.listen]. */
    data class First(val instance: SingleInstanceShell) : Outcome
    /** Another Trawl was running and has been told [command]. Exit quietly. */
    data object Forwarded : Outcome
    /** Another Trawl holds the lock but does not answer. Never start a second one on the same index: exit with a message. */
    data object Unreachable : Outcome
  }

  companion object {
    val COMMANDS = setOf("show", "quick")

    /** The socket path: in the data folder, or (if that path is too long for a socket, ~100 bytes) in the temp folder under a hash of it. */
    fun socketFor(dataDir: File): File {
      val inside = File(dataDir, "instance.sock")
      if (inside.absolutePath.toByteArray().size < 92) return inside
      val h = MessageDigest.getInstance("SHA-256").digest(dataDir.absolutePath.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
      return File(System.getProperty("java.io.tmpdir"), "trawl-$h.sock")
    }

    /**
     * Become the first instance, or tell the running one [command] (when the lock is held). [retries] gives a Trawl that is still starting
     * (lock taken, socket not bound yet) time to be ready.
     */
    fun acquire(dataDir: File, command: String = "show", retries: Int = 15, waitMs: Long = 200): Outcome {
      dataDir.mkdirs()
      val ch = FileChannel.open(File(dataDir, "instance.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      val lock = try { ch.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null } catch (_: IOException) { null }
      val sock = socketFor(dataDir)
      if (lock != null) {
        // We hold the lock, so any socket file is a leftover from a crash.
        runCatching { Files.deleteIfExists(sock.toPath()) }
        return try {
          val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
          server.bind(UnixDomainSocketAddress.of(sock.toPath()))
          runCatching { Files.setPosixFilePermissions(sock.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
          Outcome.First(SingleInstanceShell(ch, lock, server, sock))
        } catch (e: IOException) {
          // No socket (an old OS without Unix sockets): run anyway, as the only holder of the lock, just without hand-over.
          Outcome.First(SingleInstanceShell(ch, lock, null, sock))
        }
      }
      ch.close()
      repeat(retries) {
        if (send(sock, command)) return Outcome.Forwarded
        Thread.sleep(waitMs)
      }
      return Outcome.Unreachable
    }

    /** How long either side waits for the other to speak. A running Trawl answers in milliseconds; a hung one must not hang the second launch. */
    const val WAIT_MS = 1500L

    private fun send(sock: File, command: String): Boolean = try {
      SocketChannel.open(StandardProtocolFamily.UNIX).use { c ->
        c.connect(UnixDomainSocketAddress.of(sock.toPath()))
        c.write(ByteBuffer.wrap("$command\n".toByteArray()))
        readLine(c, WAIT_MS) != null
      }
    } catch (_: IOException) { false }

    /** One line from [c], or null if none arrived within [ms] (or the other side closed without one). Polls, because a channel read has no timeout. */
    internal fun readLine(c: SocketChannel, ms: Long): String? {
      c.configureBlocking(false)
      val buf = ByteBuffer.allocate(64)
      val end = System.nanoTime() + ms * 1_000_000
      while (System.nanoTime() < end) {
        val n = c.read(buf)
        if (n < 0) break
        if (buf.position() > 0 && buf.array().take(buf.position()).contains('\n'.code.toByte())) break
        if (n == 0) Thread.sleep(10)
        if (!buf.hasRemaining()) break
      }
      return if (buf.position() == 0) null else String(buf.array(), 0, buf.position()).lineSequence().first().trim()
    }
  }
}
