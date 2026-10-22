// Trawl desktop spike: a separate Gradle build that compiles the Android app's pure-Kotlin engine sources in place
// (search/, index/, parts of core/ and engine/) next to desktop-only code. Android's build is untouched.
import java.io.File
import java.net.URI
import java.security.MessageDigest

pluginManagement {
  repositories { mavenCentral(); gradlePluginPortal() }
}

// sherpa-onnx (speech to text) is not on Maven Central: its JVM jars are release assets on GitHub. They live in a small local Maven
// repository, desktop/libs/repo (the JVM jar and every POM are committed, so the Licences screen can be generated from the classpath like
// every other library). The native jar for THIS build's operating system is not committed (8 to 11 MB each); it is fetched once from the
// pinned release tag and must match the SHA-256 in desktop/libs/SHA256SUMS, or the build stops. An existing file that matches is never
// fetched again. Nothing is fetched when the app runs: this is build time only.
val sherpaVersion = "1.13.8"
val sherpaTag = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion"
val libsDir = settingsDir.resolve("libs")
val sherpaRepo = libsDir.resolve("repo/com/k2fsa/sherpa/onnx")

fun sha256Of(f: File): String {
  val md = MessageDigest.getInstance("SHA-256")
  f.inputStream().use { input -> val buf = ByteArray(1 shl 16); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
  return md.digest().joinToString("") { "%02x".format(it) }
}

fun pinnedSums(): Map<String, String> = libsDir.resolve("SHA256SUMS").readLines()
  .filter { it.isNotBlank() && !it.startsWith("#") }.associate { l -> l.substringAfter("  ").trim() to l.substringBefore("  ").trim() }

fun ensurePinned(target: File, fileName: String) {
  val want = pinnedSums()[fileName] ?: throw GradleException("desktop/libs/SHA256SUMS has no entry for $fileName")
  if (target.isFile && sha256Of(target) == want) return
  if (target.isFile) throw GradleException("${target.path} does not match the pinned SHA-256 in desktop/libs/SHA256SUMS. Delete it to fetch the pinned copy again.")
  println("Fetching $fileName from the pinned release tag v$sherpaVersion (checked against desktop/libs/SHA256SUMS)")
  target.parentFile.mkdirs()
  val part = File(target.path + ".part")
  URI("$sherpaTag/$fileName").toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
  val got = sha256Of(part)
  if (got != want) { part.delete(); throw GradleException("$fileName: SHA-256 $got, but desktop/libs/SHA256SUMS pins $want. Refusing to use it.") }
  if (!part.renameTo(target)) throw GradleException("could not move $fileName into place")
}

val osName = System.getProperty("os.name").lowercase()
val osArch = System.getProperty("os.arch").lowercase()
val sherpaPlatform: String = run {
  val arm = osArch == "aarch64" || osArch == "arm64"
  when {
    osName.contains("mac") -> if (arm) "osx-aarch64" else "osx-x64"
    osName.contains("win") -> if (arm) "win-arm64" else "win-x64"
    else -> if (arm) "linux-aarch64" else "linux-x64"
  }
}
ensurePinned(sherpaRepo.resolve("sherpa-onnx-jvm/$sherpaVersion/sherpa-onnx-jvm-$sherpaVersion.jar"), "sherpa-onnx-jvm-$sherpaVersion.jar")
ensurePinned(sherpaRepo.resolve("sherpa-onnx-native-lib-$sherpaPlatform/$sherpaVersion/sherpa-onnx-native-lib-$sherpaPlatform-$sherpaVersion.jar"),
  "sherpa-onnx-native-lib-$sherpaPlatform-$sherpaVersion.jar")
(gradle as org.gradle.api.plugins.ExtensionAware).extensions.extraProperties["sherpaPlatform"] = sherpaPlatform
(gradle as org.gradle.api.plugins.ExtensionAware).extensions.extraProperties["sherpaVersion"] = sherpaVersion

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenCentral(); google()
    maven { url = libsDir.resolve("repo").toURI(); content { includeGroup("com.k2fsa.sherpa.onnx") } }
  }
}
rootProject.name = "trawl-desktop"
