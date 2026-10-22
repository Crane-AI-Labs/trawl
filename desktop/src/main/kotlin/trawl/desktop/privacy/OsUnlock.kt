package trawl.desktop.privacy

import java.io.File
import java.util.concurrent.TimeUnit
import trawl.desktop.Os
import trawl.desktop.Platform

/** The computer's own "is it you?" prompt, for the app lock. Only yes or no ever reaches Trawl: the system keeps the secret. */
enum class OsPrompt(val id: String) {
  /** macOS: Touch ID, with the Mac's login password on the same sheet. */
  TOUCH_ID("touch_id"),
  /** macOS without Touch ID hardware (a Mac mini, a closed-lid Mac on a desktop): the login password only. */
  MAC_PASSWORD("mac_password"),
  /** Windows Hello (face, fingerprint or PIN). */
  WINDOWS_HELLO("windows_hello"),
}

sealed interface OsAvailability {
  data class Available(val prompt: OsPrompt) : OsAvailability
  /** [why] is for the log and the self-check, never for a file path or a name. */
  data class Unavailable(val why: String) : OsAvailability
}

enum class OsOutcome { OK, DENIED, CANCELLED, UNAVAILABLE, ERROR }

interface OsUnlock {
  /** Whether the system prompt can be used right now. Blocking, quick, and it NEVER shows a prompt. */
  fun availability(): OsAvailability

  /** Shows the system prompt with [reason] and waits for the answer. Blocking: call it off the UI thread. */
  fun authenticate(reason: String): OsOutcome
}

/** Linux, and any computer whose helper is missing: no system prompt, the passphrase is the lock. */
class NoOsUnlock(private val why: String = "no system prompt on this computer") : OsUnlock {
  override fun availability(): OsAvailability = OsAvailability.Unavailable(why)
  override fun authenticate(reason: String): OsOutcome = OsOutcome.UNAVAILABLE
}

/** Runs a command and returns its first stdout line, or null if it could not run or did not finish in [timeoutMs]. Injected so the tests need no real helper. */
fun interface HelperRunner {
  fun run(command: List<String>, timeoutMs: Long): String?

  companion object {
    val Process = HelperRunner { command, timeoutMs ->
      try {
        val p = ProcessBuilder(command).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        p.outputStream.close()
        // Read on this thread but give up when the clock runs out: the prompt itself has no timeout, so the person gets minutes, not forever.
        val reader = java.util.concurrent.CompletableFuture.supplyAsync { p.inputStream.bufferedReader().readLine() }
        val line = try { reader.get(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: Exception) { null }
        if (line == null) p.destroyForcibly() else p.waitFor(2, TimeUnit.SECONDS)
        line?.trim()
      } catch (_: Exception) {
        null
      }
    }
  }
}

/** The shared reading of a helper's one-line answer (the protocol in native/touchid.swift and native/hello-windows.ps1). */
internal object HelperProtocol {
  fun availability(line: String?, prompts: Map<String, OsPrompt>): OsAvailability = when {
    line == null -> OsAvailability.Unavailable("helper did not answer")
    line.startsWith("available") -> prompts[line]?.let { OsAvailability.Available(it) } ?: OsAvailability.Unavailable("unknown answer")
    line.startsWith("unavailable:") -> OsAvailability.Unavailable(line.removePrefix("unavailable:").take(60))
    else -> OsAvailability.Unavailable("unknown answer")
  }

  fun outcome(line: String?): OsOutcome = when {
    line == null -> OsOutcome.ERROR
    line == "ok" -> OsOutcome.OK
    line == "denied" -> OsOutcome.DENIED
    line == "cancelled" -> OsOutcome.CANCELLED
    line.startsWith("unavailable") -> OsOutcome.UNAVAILABLE
    else -> OsOutcome.ERROR
  }
}

/** Touch ID through `touchid-apple` (native/touchid.swift, compiled into the app by the build). */
class MacTouchId(private val helper: File, private val runner: HelperRunner = HelperRunner.Process) : OsUnlock {
  override fun availability(): OsAvailability = HelperProtocol.availability(
    runner.run(listOf(helper.absolutePath, "check"), CHECK_MS),
    mapOf("available:biometry" to OsPrompt.TOUCH_ID, "available:password" to OsPrompt.MAC_PASSWORD),
  )

  override fun authenticate(reason: String): OsOutcome = HelperProtocol.outcome(runner.run(listOf(helper.absolutePath, "auth", reason), PROMPT_MS))

  private companion object { const val CHECK_MS = 5_000L; const val PROMPT_MS = 5 * 60_000L }
}

/** Windows Hello through `hello-windows.ps1` (Windows PowerShell 5.1, which can reach the WinRT UserConsentVerifier). */
class WindowsHello(private val script: File, private val runner: HelperRunner = HelperRunner.Process) : OsUnlock {
  private fun command(vararg tail: String) = listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.absolutePath) + tail

  override fun availability(): OsAvailability = HelperProtocol.availability(runner.run(command("check"), CHECK_MS), mapOf("available" to OsPrompt.WINDOWS_HELLO))

  override fun authenticate(reason: String): OsOutcome = HelperProtocol.outcome(runner.run(command("auth", reason), PROMPT_MS))

  private companion object { const val CHECK_MS = 20_000L; const val PROMPT_MS = 5 * 60_000L }
}

object OsUnlocks {
  /** The right prompt for this computer, with its helper copied out of the app next to the index. */
  fun choose(dataDir: File, os: Os = Platform.os, runner: HelperRunner = HelperRunner.Process): OsUnlock = try {
    when (os) {
      Os.MAC -> {
        val built = OsUnlocks::class.java.classLoader.getResource("touchid-apple") != null
        if (built) MacTouchId(extract("touchid-apple", File(dataDir, "helpers")).also { it.setExecutable(true) }, runner)
        else NoOsUnlock("the Touch ID helper is not part of this build")
      }
      Os.WINDOWS -> WindowsHello(extract("hello-windows.ps1", File(dataDir, "helpers")), runner)
      Os.LINUX -> NoOsUnlock("Linux has no system prompt to use")
    }
  } catch (e: Exception) {
    NoOsUnlock("helper could not be set up: ${e.javaClass.simpleName}")
  }

  private fun extract(name: String, dir: File): File {
    dir.mkdirs()
    val out = File(dir, name)
    val bytes = OsUnlocks::class.java.classLoader.getResourceAsStream(name)?.use { it.readBytes() } ?: error("helper $name is missing from the app")
    if (!out.isFile || !out.readBytes().contentEquals(bytes)) out.writeBytes(bytes)
    return out
  }
}
