package trawl.desktop.privacy

/**
 * Can the "looks private" scorer run on this computer? It needs ONNX Runtime's native library for this operating system and processor. The
 * answer is asked once and kept. Where it is no, Trawl shows the Privacy pack as "Not available on this computer" and keeps the blur off:
 * it never presents the pack as installed or working where the scorer cannot load.
 *
 * Intel Macs are out of scope (ONNX Runtime ships no Intel Mac build, and neither do the other native libraries Trawl uses), so a Mac that
 * reports an Intel processor is a plain no without trying to load anything.
 */
object PrivacyRuntime {
  /** What was found out the first time; null until asked. */
  @Volatile private var cached: Boolean? = null

  /** A test's answer, used in place of the real probe. */
  @Volatile var override: Boolean? = null

  fun usable(): Boolean = override ?: cached ?: probe().also { cached = it }

  /** The real check, uncached: a Mac on Intel is a no; otherwise the native library must load and make an environment. */
  fun probe(osName: String = System.getProperty("os.name").orEmpty(), arch: String = System.getProperty("os.arch").orEmpty()): Boolean {
    if (isIntelMac(osName, arch)) return false
    return try {
      WindowsVcRuntime.preload(osName)   // Windows: onnxruntime.dll imports MSVCP140_1.dll, which a clean PC lacks; the app carries its own copy
      NoTelemetry.environment()   // the only way to make the ONNX environment: see NoTelemetry
      true
    } catch (_: Throwable) {   // UnsatisfiedLinkError, NoClassDefFoundError, OrtException: all mean "cannot run here"
      false
    }
  }

  fun isIntelMac(osName: String, arch: String): Boolean {
    val mac = osName.startsWith("Mac", ignoreCase = true) || osName.contains("darwin", ignoreCase = true)
    val intel = arch.equals("x86_64", ignoreCase = true) || arch.equals("amd64", ignoreCase = true) || arch.equals("x64", ignoreCase = true)
    return mac && intel
  }
}

/**
 * ONNX Runtime's Windows library (`onnxruntime.dll`, 1.28) imports `MSVCP140_1.dll`, a piece of the Visual C++ runtime that a clean Windows
 * (a new PC, a fresh Windows Server) does not have and that the bundled Java runtime does not carry (it ships `msvcp140.dll`, `vcruntime140.dll`
 * and `vcruntime140_1.dll` only). Without it the library fails to load ("Can't find dependent libraries"), the Privacy pack reads "Not available
 * on this computer" and the blur never scores a picture. The build copies the DLL next to the app's resources (`stageWindowsVcRuntime` in
 * desktop/build.gradle.kts); this loads it first, so Windows finds it already in the process when ONNX Runtime asks for it by name.
 * Nothing here touches the network; it is a local file in the install folder.
 */
object WindowsVcRuntime {
  const val DLL = "msvcp140_1.dll"

  /** The result of the last try: "not windows", "loaded <path>", "no bundled copy" or the failure; for the diagnostics and the gate. */
  @Volatile var lastResult: String = "not tried"

  fun preload(osName: String = System.getProperty("os.name").orEmpty(), resourcesDir: String? = System.getProperty("compose.application.resources.dir")) {
    if (!osName.startsWith("Windows", ignoreCase = true)) { lastResult = "not windows"; return }
    val f = resourcesDir?.let { java.io.File(it, DLL) }
    if (f == null || !f.isFile) { lastResult = "no bundled copy"; return }
    lastResult = try { System.load(f.absolutePath); "loaded" } catch (t: Throwable) { "failed: ${t.javaClass.simpleName}" }
  }
}
