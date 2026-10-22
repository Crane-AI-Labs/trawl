package trawl.desktop

import java.io.File

/**
 * Which Vulkan drivers a Linux computer has installed, read from the loader's own driver list (the `.json` files in `icd.d`) without loading any of them.
 * Software drivers (Mesa's lavapipe, llvmpipe, SwiftShader) run Vulkan on the CPU: the picture model gains nothing from them, and the GPU
 * probe ([GpuProbe]) crashed the probe process inside one (matrix 2026-10-22: SIGSEGV on Ubuntu with only lavapipe, a 1 GB core file and
 * about 95 s lost). A computer whose every driver is a software one goes straight to the CPU path.
 *
 * This reads small JSON files from disk. Nothing is run and nothing is sent anywhere.
 */
object VulkanIcds {
  /** One driver entry: the manifest's file name and the library it points to. */
  data class Icd(val manifest: String, val library: String)

  private val SOFTWARE = listOf("lvp", "lavapipe", "llvmpipe", "swrast", "softpipe", "swiftshader", "vk_swiftshader", "cpu")

  /** True for a driver that runs on the CPU: named for one of the software renderers (matched as a whole word-part, so "cpu" does not hit "gpu_x"). */
  fun isSoftware(icd: Icd): Boolean {
    val words = (icd.manifest + " " + icd.library).lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    return words.any { w -> SOFTWARE.any { s -> w == s || (s.length > 3 && w.contains(s)) } }
  }

  /**
   * True when there is at least one driver and every one is a software one. No drivers at all is "cannot tell" (the loader can also find a
   * driver by other means), so that case is left to the probe, which is safe to run.
   */
  fun onlySoftware(icds: List<Icd>): Boolean = icds.isNotEmpty() && icds.all(::isSoftware)

  /** The folders the Vulkan loader searches for manifests (the spec's list, in order), or the files `VK_DRIVER_FILES` / `VK_ICD_FILENAMES` name. */
  fun manifestDirs(env: Map<String, String> = System.getenv(), home: String = System.getProperty("user.home")): List<File> {
    val dataDirs = (env["XDG_DATA_DIRS"]?.takeIf { it.isNotBlank() } ?: "/usr/local/share:/usr/share").split(':').filter { it.isNotBlank() }
    val dataHome = env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() } ?: "$home/.local/share"
    val configDirs = (env["XDG_CONFIG_DIRS"]?.takeIf { it.isNotBlank() } ?: "/etc/xdg").split(':').filter { it.isNotBlank() }
    return (listOf("/etc/vulkan/icd.d") + configDirs.map { "$it/vulkan/icd.d" } + listOf("$dataHome/vulkan/icd.d") + dataDirs.map { "$it/vulkan/icd.d" })
      .distinct().map(::File)
  }

  /** The installed drivers. [files] is a test seam; by default the environment decides where to look. */
  fun installed(
    env: Map<String, String> = System.getenv(),
    home: String = System.getProperty("user.home"),
    read: (File) -> String? = { f -> runCatching { f.takeIf { it.isFile && it.length() < 100_000 }?.readText() }.getOrNull() },
    list: (File) -> List<File> = { d -> runCatching { d.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } }.getOrNull() ?: emptyList() },
  ): List<Icd> {
    val explicit = (env["VK_DRIVER_FILES"] ?: env["VK_ICD_FILENAMES"])?.takeIf { it.isNotBlank() }
    val files = if (explicit != null) explicit.split(':', ';').filter { it.isNotBlank() }.map(::File) else manifestDirs(env, home).flatMap(list)
    return files.mapNotNull { f ->
      val text = read(f) ?: return@mapNotNull null
      Icd(f.name, Regex("\"library_path\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: "")
    }
  }

  /** True on a Linux computer whose only Vulkan drivers are software ones. Other systems have no such list to read. */
  fun onlySoftwareHere(os: Os = Platform.os): Boolean = os == Os.LINUX && onlySoftware(installed())
}
