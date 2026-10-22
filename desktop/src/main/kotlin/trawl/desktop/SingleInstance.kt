package trawl.desktop

import java.awt.Window
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * One Trawl per data folder. A second launch does not open a second window or a second index: it tells the first to come forward and
 * exits. Two Trawls on one index would fight over its file (the "busy" calm card), so the lock is taken before the index is opened.
 *
 * The lock is [FileChannel.tryLock] on `instance.lock` in the data folder: the operating system drops it when the process dies, however
 * it dies, so a crash never leaves a stale lock. The hand-over is a Unix domain socket (`instance.sock`, readable by the person only),
 * not a TCP port: Trawl has no network code at all and a loopback port is still the network stack. A command is one line:
 * `show` (come forward) or `quick` (open the quick panel).
 *
 * Same file names, same one-line protocol and same `acquire` / `Outcome` shape as `shell/SingleInstanceShell.kt` (the shell package's
 * minimal version, on the desktop-shell branch), so the two interoperate and, when both are merged, the shell's call is a one-word
 * swap to this class and its file can be deleted. This one adds what the window needs: [focus] (which really brings a window forward on
 * macOS, Windows and Linux), the process id in the lock file for the problem report, and a plain message when the first Trawl does not
 * answer.
 */
class SingleInstance private constructor(
  private val lockChannel: FileChannel,
  private val lock: FileLock,
  private val server: ServerSocketChannel?,
  private val socketFile: File,
) : AutoCloseable {
  @Volatile private var closed = false

  /** Starts answering second launches. [onCommand] runs on a background thread, with "show" or "quick". */
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
    /** This is the only Trawl: run, and call [SingleInstance.listen]. */
    data class First(val instance: SingleInstance) : Outcome
    /** Another Trawl was running and has been told the command. Exit quietly. */
    data object Forwarded : Outcome
    /** Another Trawl holds the lock but does not answer. Never start a second one on the same index: exit with a message. */
    data object Unreachable : Outcome
  }

  companion object {
    val COMMANDS = setOf("show", "quick")

    /** How long either side waits for the other to speak. A running Trawl answers in milliseconds; a hung one must not hang the second launch. */
    const val WAIT_MS = 1500L

    /** The socket path: in the data folder, or (if that path is too long for a socket, about 100 bytes) in the temp folder under a hash of it. */
    fun socketFor(dataDir: File): File {
      val inside = File(dataDir, "instance.sock")
      if (inside.absolutePath.toByteArray().size < 92) return inside
      val h = MessageDigest.getInstance("SHA-256").digest(dataDir.absolutePath.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
      return File(System.getProperty("java.io.tmpdir"), "trawl-$h.sock")
    }

    /**
     * Becomes the first instance, or tells the running one [command] when the lock is held. [retries] gives a Trawl that is still starting
     * (lock taken, socket not bound yet) time to be ready.
     */
    fun acquire(dataDir: File, command: String = "show", retries: Int = 15, waitMs: Long = 200): Outcome {
      dataDir.mkdirs()
      val ch = FileChannel.open(File(dataDir, "instance.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      val lock = try { ch.tryLock() } catch (_: OverlappingFileLockException) { null } catch (_: IOException) { null }
      val sock = socketFor(dataDir)
      if (lock != null) {
        runCatching { ch.truncate(0); ch.write(ByteBuffer.wrap("${ProcessHandle.current().pid()}\n".toByteArray()), 0) }   // for the problem report, not for locking
        // We hold the lock, so any socket file is a leftover from a crash.
        runCatching { Files.deleteIfExists(sock.toPath()) }
        return try {
          val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
          server.bind(UnixDomainSocketAddress.of(sock.toPath()))
          runCatching { Files.setPosixFilePermissions(sock.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
          Outcome.First(SingleInstance(ch, lock, server, sock))
        } catch (_: IOException) {
          // No Unix sockets (an old OS): run anyway as the only holder of the lock, just without the hand-over.
          Outcome.First(SingleInstance(ch, lock, null, sock))
        } catch (_: UnsupportedOperationException) {
          Outcome.First(SingleInstance(ch, lock, null, sock))
        }
      }
      ch.close()
      repeat(retries) {
        if (send(sock, command)) return Outcome.Forwarded
        Thread.sleep(waitMs)
      }
      return Outcome.Unreachable
    }

    /**
     * The start-up call: returns the first instance, or ends the process. A second launch that reached the first exits quietly (code 0);
     * one that found a Trawl that does not answer says so in a plain sentence (code 1) instead of opening a second index on the same file.
     */
    fun firstOrExit(dataDir: File, command: String = "show", exit: (Int) -> Nothing = { kotlin.system.exitProcess(it) }): SingleInstance =
      when (val o = acquire(dataDir, command)) {
        is Outcome.First -> o.instance
        Outcome.Forwarded -> exit(0)
        Outcome.Unreachable -> {
          val message = t("single_unreachable", t("app_name"))
          if (java.awt.GraphicsEnvironment.isHeadless()) System.err.println(message)
          else runCatching { javax.swing.JOptionPane.showMessageDialog(null, message, t("app_name"), javax.swing.JOptionPane.INFORMATION_MESSAGE) }
          exit(1)
        }
      }

    private fun send(sock: File, command: String): Boolean = try {
      SocketChannel.open(StandardProtocolFamily.UNIX).use { c ->
        c.connect(UnixDomainSocketAddress.of(sock.toPath()))
        c.write(ByteBuffer.wrap("$command\n".toByteArray()))
        readLine(c, WAIT_MS) != null
      }
    } catch (_: IOException) {
      false
    } catch (_: UnsupportedOperationException) {
      false
    }

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

    /**
     * Brings [window] in front of the others and gives it the keyboard, from any thread. A window manager is allowed to refuse a window
     * that takes focus by itself (Windows only flashes the taskbar), so the always-on-top flip is the portable way to make it come up, and
     * macOS is asked to bring the whole application forward.
     */
    fun focus(window: Window) {
      java.awt.EventQueue.invokeLater {
        runCatching {
          (window as? java.awt.Frame)?.let { if (it.state == java.awt.Frame.ICONIFIED) it.state = java.awt.Frame.NORMAL }
          window.isVisible = true
          val wasOnTop = window.isAlwaysOnTop
          window.isAlwaysOnTop = true
          window.toFront()
          window.requestFocus()
          window.isAlwaysOnTop = wasOnTop
        }
        runCatching { if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.APP_REQUEST_FOREGROUND)) java.awt.Desktop.getDesktop().requestForeground(true) }
      }
    }
  }
}
