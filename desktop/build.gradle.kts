import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  kotlin("jvm") version "2.3.20"
  id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
  id("org.jetbrains.compose") version "1.12.2"
}

kotlin { jvmToolchain(21) }

// Trawl's shared engine is the :core module (../core). Its code is plain Kotlin on the JDK, in `src/jvmCommonMain`: nothing in it
// needs the Android toolchain and it holds no expect/actual, so this build compiles that one source tree in place instead of
// pulling in the Android Gradle build. Same files the phone compiles, no copy, no shims. The phone-only parts (Context, MediaStore,
// WorkManager, Compose) stay in ../app and are not part of it. Platform seams the engine asks the host for are set in Main:
// AssetSource (ClasspathAssets), LogSinks.sink, BuildFlags and Diag.init(DiagPlatform, ...).
val core = rootDir.resolve("../core/src/jvmCommonMain/kotlin")
sourceSets.main {
  kotlin.srcDir(core)
  // SettingsCodec is left out: only FindrEngine (phone-only) uses it, and its `keys().asSequence()` needs the generic Iterator<String> of today's
  // Android org.json, which the 2013 Apache-2.0 copy lacks. Put it back with a typed org.json.
  kotlin.exclude("**/core/SettingsCodec.kt")
  resources.srcDir(rootDir.resolve("../app/src/main/assets"))   // the word lists and language centroids the engine reads
  resources.srcDir(rootDir.resolve("../app/src/main/res/font")) // Atkinson Hyperlegible, the same two weights the phone bundles
  resources.srcDir(rootDir.resolve("native"))                    // the OCR helper scripts: extracted next to the index at run time
  resources.srcDir(layout.buildDirectory.dir("native"))          // the Apple Vision helper, compiled by buildAppleOcr (macOS builds only)
  resources.srcDir(layout.buildDirectory.dir("generated/licences")) // trawl/licences.tsv and trawl/notices/: what the Licences screen lists, from generateLicences
}

// Apple Vision OCR is a small Swift program (native/ocr-stdin.swift). On a Mac it is compiled here, once, into the app, so the person
// needs no developer tools; on Linux and Windows this task is skipped and nothing is added. Apple silicon only (the model has no Intel Mac build).
val buildAppleOcr = tasks.register<Exec>("buildAppleOcr") {
  val src = file("native/ocr-stdin.swift")
  val out = layout.buildDirectory.file("native/ocr-stdin-apple")
  onlyIf { System.getProperty("os.name").startsWith("Mac") }
  inputs.file(src); outputs.file(out)
  doFirst { out.get().asFile.parentFile.mkdirs() }
  commandLine("swiftc", "-O", "-target", "arm64-apple-macos13.0", src.absolutePath, "-o", out.get().asFile.absolutePath)
}
// The Mac reads videos and recordings with AVFoundation through a second small Swift program (native/media-apple.swift), compiled here
// the same way. Elsewhere this task is skipped (Windows uses native/media-windows.ps1, Linux the person's ffmpeg).
val buildAppleMedia = tasks.register<Exec>("buildAppleMedia") {
  val src = file("native/media-apple.swift")
  val out = layout.buildDirectory.file("native/media-apple")
  onlyIf { System.getProperty("os.name").startsWith("Mac") }
  inputs.file(src); outputs.file(out)
  doFirst { out.get().asFile.parentFile.mkdirs() }
  commandLine("swiftc", "-O", "-suppress-warnings", "-target", "arm64-apple-macos13.0", src.absolutePath, "-o", out.get().asFile.absolutePath)
}
tasks.named("processResources") { dependsOn(buildAppleOcr, buildAppleMedia) }

// --- shell (Package D) ---
// The macOS global-shortcut helper (native/hotkey-mac.swift, Carbon RegisterEventHotKey: no Accessibility permission), compiled and shipped the
// same way as the OCR helper: once, on a Mac build, into build/native; skipped on Linux and Windows.
val buildMacHotkey = tasks.register<Exec>("buildMacHotkey") {
  val src = file("native/hotkey-mac.swift")
  val out = layout.buildDirectory.file("native/hotkey-mac")
  onlyIf { System.getProperty("os.name").startsWith("Mac") }
  inputs.file(src); outputs.file(out)
  doFirst { out.get().asFile.parentFile.mkdirs() }
  commandLine("swiftc", "-O", "-target", "arm64-apple-macos13.0", src.absolutePath, "-o", out.get().asFile.absolutePath)
}
tasks.named("processResources") { dependsOn(buildMacHotkey) }
// --- end shell ---

// The Touch ID helper (native/touchid.swift): same arrangement as the OCR helper. Compiled on a Mac into the app; skipped elsewhere.
// Windows Hello needs no build (native/hello-windows.ps1 is a script); Linux has no OS prompt and uses the passphrase.
val buildTouchId = tasks.register<Exec>("buildTouchId") {
  val src = file("native/touchid.swift")
  val out = layout.buildDirectory.file("native/touchid-apple")
  onlyIf { System.getProperty("os.name").startsWith("Mac") }
  inputs.file(src); outputs.file(out)
  doFirst { out.get().asFile.parentFile.mkdirs() }
  commandLine("swiftc", "-O", "-target", "arm64-apple-macos13.0", src.absolutePath, "-o", out.get().asFile.absolutePath)
}
tasks.named("processResources") { dependsOn(buildTouchId) }

// The macOS share sheet (native/share-apple.swift, NSSharingServicePicker) for "Share": same arrangement. Compiled on a Mac into the app; Windows
// uses native/share-windows.ps1 (a script) and Linux has no system share sheet, so nothing is built there and Share is not offered.
val buildAppleShare = tasks.register<Exec>("buildAppleShare") {
  val src = file("native/share-apple.swift")
  val out = layout.buildDirectory.file("native/share-apple")
  onlyIf { System.getProperty("os.name").startsWith("Mac") }
  inputs.file(src); outputs.file(out)
  doFirst { out.get().asFile.parentFile.mkdirs() }
  commandLine("swiftc", "-O", "-suppress-warnings", "-target", "arm64-apple-macos13.0", src.absolutePath, "-o", out.get().asFile.absolutePath)
}
tasks.named("processResources") { dependsOn(buildAppleShare) }

// The macOS "full preview" for the quick-search panel (native/quicklook-apple.swift, the system's QLPreviewPanel): same arrangement. Compiled on a
// Mac into the app; Windows and Linux use the in-app preview only, so nothing is built there.
val buildAppleQuickLook = tasks.register<Exec>("buildAppleQuickLook") {
  val src = file("native/quicklook-apple.swift")
  val out = layout.buildDirectory.file("native/quicklook-apple")
  onlyIf { System.getProperty("os.name").startsWith("Mac") }
  inputs.file(src); outputs.file(out)
  doFirst { out.get().asFile.parentFile.mkdirs() }
  commandLine("swiftc", "-O", "-suppress-warnings", "-target", "arm64-apple-macos13.0", src.absolutePath, "-o", out.get().asFile.absolutePath)
}
tasks.named("processResources") { dependsOn(buildAppleQuickLook) }

// The third-party list the Licences screen shows is generated from what the desktop really ships: every jar on the resolved
// runtimeClasspath (transitive ones too), with the licence read from its own POM (or the parent POM it inherits one from). The
// NOTICE file inside a jar is copied out too, because Apache-2.0 section 4(d) makes it part of the redistribution. Nothing is
// typed by hand except LICENCE_OVERRIDES: an artifact whose POM names no licence FAILS the build instead of being guessed,
// unless it is listed there with the reason. Output: build/generated/licences/trawl/licences.tsv (group, artifact, version,
// licences joined by " | ", project url, notice file or "-") and trawl/notices/<group>_<artifact>.txt.
data class LicCoord(val group: String, val module: String, val version: String) { val key get() = "$group:$module" }
data class LicPom(val licences: List<String>, val url: String?)

// group:artifact -> (licence, url) for a POM that names none. Each entry says why.
val LICENCE_OVERRIDES: Map<String, Pair<String, String>> = mapOf()

fun licPomFile(deps: DependencyHandler, c: LicCoord): File {
  val result = deps.createArtifactResolutionQuery()
    .forModule(c.group, c.module, c.version).withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java).execute()
  return result.resolvedComponents.flatMap { it.getArtifacts(MavenPomArtifact::class.java) }
    .filterIsInstance<ResolvedArtifactResult>().firstOrNull()?.file ?: throw GradleException("No POM found for ${c.key}:${c.version}")
}

fun licKids(e: org.w3c.dom.Element, name: String): List<org.w3c.dom.Element> =
  (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<org.w3c.dom.Element>().filter { it.tagName == name }

fun licKid(e: org.w3c.dom.Element, name: String): String? =
  licKids(e, name).firstOrNull()?.textContent?.trim()?.replace(Regex("\\s+"), " ")

fun licReadPom(deps: DependencyHandler, c: LicCoord, depth: Int = 0): LicPom {
  val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
  factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
  val root = factory.newDocumentBuilder().parse(licPomFile(deps, c)).documentElement
  val names = licKids(root, "licenses").flatMap { licKids(it, "license") }.mapNotNull { licKid(it, "name") ?: licKid(it, "url") }
  val url = licKid(root, "url")
  val parent = licKids(root, "parent").firstOrNull()
  if ((names.isEmpty() || url == null) && parent != null && depth < 6) {
    val pv = licKid(parent, "version")!!.replace("\${project.version}", c.version)
    val p = licReadPom(deps, LicCoord(licKid(parent, "groupId")!!, licKid(parent, "artifactId")!!, pv), depth + 1)
    return LicPom(names.ifEmpty { p.licences }, url ?: p.url)
  }
  return LicPom(names, url)
}

fun licNoticeOf(jar: File): String? = ZipFile(jar).use { z ->
  val entry = listOf("META-INF/NOTICE", "META-INF/NOTICE.txt", "META-INF/NOTICE.md").firstNotNullOfOrNull { z.getEntry(it) }
  entry?.let { z.getInputStream(it).readBytes().toString(Charsets.UTF_8) }?.takeIf { it.isNotBlank() }
}

val generateLicences = tasks.register("generateLicences") {
  val runtime = configurations.named("runtimeClasspath")
  val outDir = layout.buildDirectory.dir("generated/licences")
  inputs.files(runtime)
  outputs.dir(outDir)
  // The POM lookup needs the live dependency handler (ArtifactResolutionQuery), which a cached configuration cannot hold.
  notCompatibleWithConfigurationCache("reads POMs through project.dependencies at execution time")
  doLast {
    val deps = project.dependencies
    val jars = HashMap<LicCoord, MutableList<File>>()
    for (a in runtime.get().incoming.artifacts.artifacts) {
      val id = a.id.componentIdentifier as? ModuleComponentIdentifier ?: continue
      jars.getOrPut(LicCoord(id.group, id.module, id.version)) { ArrayList() }.add(a.file)
    }
    if (jars.isEmpty()) throw GradleException("runtimeClasspath resolved no modules")

    val out = outDir.get().asFile
    out.deleteRecursively()
    File(out, "trawl/notices").mkdirs()
    val rows = ArrayList<String>()
    for (c in jars.keys.sortedBy { it.key }) {
      val pom = licReadPom(deps, c)
      val override = LICENCE_OVERRIDES[c.key]
      val names = pom.licences.ifEmpty { override?.let { listOf(it.first) } }
        ?: throw GradleException("The POM of ${c.key}:${c.version} names no licence. Read its licence and add it to LICENCE_OVERRIDES in desktop/build.gradle.kts with the reason; do not guess.")
      var notice = "-"
      for (jar in jars.getValue(c).filter { it.name.endsWith(".jar") }) {
        val text = licNoticeOf(jar)
        if (text != null && notice == "-") {
          notice = "${c.group}_${c.module}.txt"
          File(out, "trawl/notices/$notice").writeText(text, Charsets.UTF_8)
        }
      }
      val url = pom.url ?: override?.second ?: ""
      rows += listOf(c.group, c.module, c.version, names.joinToString(" | "), url, notice).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
    }
    File(out, "trawl/licences.tsv").writeText(rows.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    println("generateLicences: ${rows.size} modules")
  }
}
tasks.named("processResources") { dependsOn(generateLicences) }

// Three small Compose files still live in :app (they are UI, not engine): Sunlight's colour tokens and two pure helpers (snippet
// highlighting, byte sizes). Compiled unchanged, one file each, so nothing else of :app's UI is pulled in.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
  source(fileTree(rootDir.resolve("../app/src/main/java")) {
    include("com/craneai/findr/ui/theme/Color.kt", "com/craneai/findr/ui/common/Highlight.kt", "com/craneai/findr/ui/common/Format.kt", "com/craneai/findr/engine/StoreOpener.kt")
  })
}

// ONNX Runtime's jar carries the natives of every platform (about 51 MB: Linux x64 and arm64, macOS arm64 with its debug symbols, Windows x64),
// and each installer is built on the system it is for, so each installer should carry only that system's. This artifact transform rewrites the
// ONNX Runtime jar for the runtime classpath (the installer, the tests, `run`): it keeps every class, licence and notice, keeps the natives of the
// build machine's own system (`ai/onnxruntime/native/<os>-<arch>/`), and drops the other systems' natives and the macOS `.dSYM` debug-symbol folders.
// Every other jar passes through untouched. Override the system with -PortNatives=linux-x64 (or linux-aarch64, osx-aarch64, win-x64); on a system ONNX
// Runtime ships nothing for (an Intel Mac) no natives are kept, which is what PrivacyRuntime reports as "Not available on this computer".
fun ortNativesFor(osName: String, arch: String): String? {
  val a = if (arch == "amd64" || arch == "x86_64" || arch == "x64") "x64" else if (arch == "aarch64" || arch == "arm64") "aarch64" else return null
  val os = when {
    osName.startsWith("Windows", true) -> "win"
    osName.startsWith("Mac", true) -> "osx"
    osName.startsWith("Linux", true) -> "linux"
    else -> return null
  }
  return "$os-$a".takeIf { it in setOf("linux-x64", "linux-aarch64", "osx-aarch64", "win-x64") }
}

abstract class SlimOnnxRuntime : TransformAction<SlimOnnxRuntime.Params> {
  interface Params : TransformParameters {
    /** The `<os>-<arch>` folder to keep, or empty to keep none, or "all" to leave the jar as published. */
    @get:Input val keep: Property<String>
  }

  @get:InputArtifact @get:PathSensitive(PathSensitivity.NAME_ONLY) abstract val input: Provider<FileSystemLocation>

  override fun transform(outputs: TransformOutputs) {
    val jar = input.get().asFile
    if (!jar.name.startsWith("onnxruntime-") || jar.name.startsWith("onnxruntime-extensions")) { outputs.file(jar); return }
    val keep = parameters.keep.get()
    if (keep == "all") { outputs.file(jar); return }   // -PortNatives=all: the jar as published (every system's natives), for comparing sizes
    val prefix = "ai/onnxruntime/native/"
    val out = outputs.file(jar.name)
    ZipInputStream(jar.inputStream().buffered()).use { zin ->
      ZipOutputStream(out.outputStream().buffered()).use { zout ->
        generateSequence { zin.nextEntry }.forEach { e ->
          val under = e.name.removePrefix(prefix)
          val isNative = e.name.startsWith(prefix)
          val wanted = !isNative || (under.substringBefore('/') == keep && ".dSYM" !in e.name && !e.isDirectory)
          if (wanted && !(isNative && under.isEmpty())) {
            zout.putNextEntry(ZipEntry(e.name).also { it.time = e.time })
            zin.copyTo(zout)
            zout.closeEntry()
          }
        }
      }
    }
  }
}

val slimmedOrt = Attribute.of("trawl.slimmedOrt", Boolean::class.javaObjectType)
dependencies {
  attributesSchema { attribute(slimmedOrt) }
  artifactTypes.getByName("jar") { attributes.attribute(slimmedOrt, false) }
  registerTransform(SlimOnnxRuntime::class) {
    from.attribute(slimmedOrt, false).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
    to.attribute(slimmedOrt, true).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "jar")
    parameters.keep.set(
      (findProperty("ortNatives") as String?)
        ?: (ortNativesFor(System.getProperty("os.name"), System.getProperty("os.arch")) ?: ""),
    )
  }
}
configurations.matching { it.name == "runtimeClasspath" || it.name == "testRuntimeClasspath" }.configureEach { attributes.attribute(slimmedOrt, true) }

dependencies {
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")   // Apache-2.0: the Main dispatcher on the AWT thread
  implementation("androidx.sqlite:sqlite-bundled-jvm:2.7.1")   // Apache-2.0: SQLite with FTS5 (porter), the same driver Android uses
  implementation("androidx.annotation:annotation-jvm:1.9.1")
  // Apache-2.0: the org.json classes lifted from the Android SDK (same package and API as the phone's), so the shared sources that parse JSON
  // (SignedBundle, LanguageCentroids) compile unchanged. Replaces org.json:json 2025, whose JSON licence ("Good, not Evil") is not OSI.
  implementation("com.vaadin.external.google:android-json:0.0.20131108.vaadin1")
  implementation("net.sf.kxml:kxml2:2.3.0")                      // org.xmlpull pull parser, which :core's office-text reader uses (android.jar has it on a phone)
  implementation("net.java.dev.jna:jna:5.18.1")                  // LGPL-2.1 or Apache-2.0 (dual): FSEvents / native shims later
  implementation("com.google.ai.edge.litertlm:litertlm-jvm:0.18.0")   // Apache-2.0: the same EmbeddingEngine API and .litertlm file as Android
  // MIT: ONNX Runtime, the engine that runs the Privacy pack's classifier, as on the phone. The published jar carries the natives of every platform
  // (51 MB); the runtime classpath gets it cut to this system's (see SlimOnnxRuntime above).
  // PINNED AT 1.28.0 ON PURPOSE, and never to be raised without the zero-network gate (desktop/scripts/net-gate.py): ONNX Runtime 1.29 to 1.31 built
  // Microsoft's "1DS" telemetry client into its macOS and Linux libraries, ON by default, and it opened a connection to
  // mobile.events.data.microsoft.com about ten seconds after start-up (found 2026-10-21 on a developer's Mac; internal/research/desktop-network-and-jvm.md).
  // 1.28.0 has no such code. The privacy model scores the same on 1.28.0 and 1.31.0 (logits differ by at most 2e-6 over 24 inputs).
  // checkNoTelemetryInNatives below fails the build if any native library on the classpath holds the client, so a raised version cannot slip in.
  implementation("com.microsoft.onnxruntime:onnxruntime:1.28.0")
  implementation("org.apache.pdfbox:pdfbox:3.0.8")               // Apache-2.0: PDF text
  // BSD-3-Clause: picture readers that register with javax.imageio (WebP, Photoshop, TGA, PNM, PCX, ICO/BMP); pure Java, no native code.
  for (fmt in listOf("webp", "psd", "tga", "pnm", "pcx", "bmp")) implementation("com.twelvemonkeys.imageio:imageio-$fmt:3.15.3")
  // Apache-2.0 (ONNX Runtime inside the native jar: MIT): speech to text, voice activity and sound labels, the same engine and models as the phone.
  // Release assets on GitHub, not Maven Central: the JVM jar is committed under libs/repo, the native jar for this OS is fetched from the pinned tag
  // by settings.gradle.kts and checked against libs/SHA256SUMS. See there.
  val sherpaVersion = (gradle as ExtensionAware).extra["sherpaVersion"] as String
  val sherpaPlatform = (gradle as ExtensionAware).extra["sherpaPlatform"] as String
  implementation("com.k2fsa.sherpa.onnx:sherpa-onnx-jvm:$sherpaVersion")
  runtimeOnly("com.k2fsa.sherpa.onnx:sherpa-onnx-native-lib-$sherpaPlatform:$sherpaVersion")
  implementation(compose.desktop.currentOs)                  // Apache-2.0: Compose Multiplatform runtime, foundation, ui and Skiko (the window)
  implementation(compose.material3)                              // Apache-2.0: the Material 3 components the phone's Sunlight theme is built on
  testImplementation(kotlin("test"))
  @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
  testImplementation(compose.uiTest)                           // Apache-2.0: runComposeUiTest, the semantics tree the accessibility tests read (test only)
}

tasks.test {
  useJUnitPlatform()
  // Gradle starts test JVMs headless. On a Linux box with an X display (Xvfb is enough) the X11 tests need real AWT windows, so give them one;
  // everywhere else the default stays, and those tests skip themselves.
  if (org.gradle.internal.os.OperatingSystem.current().isLinux && !System.getenv("DISPLAY").isNullOrBlank()) systemProperty("java.awt.headless", "false")
}

// ---- The Java runtime inside the installers: pinned, checksummed, downloaded into build/, never whatever the machine has ----------------------
// The first installers bundled the build machine's Temurin 21+35 (21.0.0). After 92 minutes the app aborted with "Internal Error
// (assembler_aarch64.hpp:264) guarantee(chk == -1 || chk == 0) failed: Field too big for insn": the C1 compiler on AArch64 fails when it
// compiles a hot method whose on-stack-replacement frame is large (here Compose's Recomposer loop). Three OpenJDK fixes cover it:
// JDK-8310844 (Temurin 21.0.3), JDK-8320682 (21.0.6) and JDK-8335662 (21.0.8), so 21.0.8 is the oldest runtime we will ship; we ship the
// newest 21 Temurin at the time of the fix. Every installer is built from this one archive per operating system, its SHA-256 pinned below
// (from the Adoptium API, api.adoptium.net/v3/assets/latest/21/hotspot). RuntimeInfo.MIN_SAFE in the app is the same number as MIN_SAFE_JDK.
val MIN_SAFE_JDK = "21.0.8"
val PINNED_JDK = "21.0.12.1+1"

class PinnedJdk(val key: String, val archive: String, val sha256: String)
val pinnedJdks = listOf(
  PinnedJdk("mac-aarch64", "OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.12.1_1.tar.gz", "3623232f33a9c3baadf304480b2535f9a3cba8a58d42ecbb438ba267315d9998"),
  PinnedJdk("linux-x64", "OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz", "ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94"),
  PinnedJdk("linux-aarch64", "OpenJDK21U-jdk_aarch64_linux_hotspot_21.0.12.1_1.tar.gz", "23e37e026f12f3e706f18938ff611db3032d075b09d0879a25d06718c773e223"),
  PinnedJdk("windows-x64", "OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip", "f9d6e191ab098c0d416e7d588a24420a8621cd2f4720dab2459b8b7b2d2d8b4e"),
)

fun jdkPlatformKey(osName: String, arch: String): String? {
  val a = if (arch == "amd64" || arch == "x86_64" || arch == "x64") "x64" else if (arch == "aarch64" || arch == "arm64") "aarch64" else return null
  val os = when { osName.startsWith("Windows", true) -> "windows"; osName.startsWith("Mac", true) -> "mac"; osName.startsWith("Linux", true) -> "linux"; else -> return null }
  return "$os-$a"
}

val pinnedJdk: PinnedJdk? = pinnedJdks.firstOrNull { it.key == jdkPlatformKey(System.getProperty("os.name"), System.getProperty("os.arch")) }
val jdkRoot: File = layout.buildDirectory.dir("runtime-jdk").get().asFile
val pinnedJdkHome: File? = pinnedJdk?.let { File(jdkRoot, "jdk-$PINNED_JDK" + if (it.key.startsWith("mac")) "/Contents/Home" else "") }

/** "21.0.12.1" from a JDK's `release` file (JAVA_VERSION="21.0.12.1"), or null. */
fun jdkReleaseVersion(release: File): String? =
  release.takeIf { it.isFile }?.readLines()?.firstOrNull { it.startsWith("JAVA_VERSION=") }?.substringAfter('=')?.trim('"')

fun jdkNumbers(v: String): List<Int> = (v.takeWhile { it.isDigit() || it == '.' }.split('.').mapNotNull { it.toIntOrNull() } + List(4) { 0 }).take(4)

fun jdkAtLeast(v: String, min: String): Boolean {
  val a = jdkNumbers(v); val b = jdkNumbers(min)
  if (a.all { it == 0 }) return false
  for (i in 0 until 4) if (a[i] != b[i]) return a[i] > b[i]
  return true
}

fun sha256Of(f: File): String {
  val md = MessageDigest.getInstance("SHA-256")
  f.inputStream().use { ins -> val buf = ByteArray(1 shl 20); while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
  return md.digest().joinToString("") { "%02x".format(it) }
}

fun unzipTo(archive: File, dest: File) {
  ZipFile(archive).use { z ->
    for (e in z.entries()) {
      val out = File(dest, e.name).canonicalFile
      if (!out.path.startsWith(dest.canonicalPath + File.separator)) throw GradleException("unsafe path in ${archive.name}: ${e.name}")
      if (e.isDirectory) { out.mkdirs(); continue }
      out.parentFile.mkdirs()
      z.getInputStream(e).use { ins -> out.outputStream().use { ins.copyTo(it) } }
    }
  }
}

val fetchRuntimeJdk = tasks.register("fetchRuntimeJdk") {
  group = "trawl"
  description = "Downloads the pinned Temurin $PINNED_JDK for this operating system into build/runtime-jdk and checks its SHA-256."
  val spec = pinnedJdk
  val home = pinnedJdkHome
  val root = jdkRoot
  outputs.dir(root)
  notCompatibleWithConfigurationCache("downloads and unpacks a JDK")
  doLast {
    if (spec == null || home == null) throw GradleException("No pinned Java runtime for ${System.getProperty("os.name")} ${System.getProperty("os.arch")}; Trawl's installers are built on Apple silicon, Linux x64/arm64 and Windows x64.")
    if (jdkReleaseVersion(File(home, "release")) == PINNED_JDK.substringBefore('+') && File(home, "bin").isDirectory) return@doLast
    root.deleteRecursively(); root.mkdirs()
    val archive = File(root, spec.archive)
    val given = findProperty("pinnedJdkArchive") as String?   // -PpinnedJdkArchive=/path: a copy fetched earlier (still checked against the pin)
    if (given != null) File(given).copyTo(archive, overwrite = true) else {
      val url = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-" + PINNED_JDK.replace("+", "%2B") + "/" + spec.archive
      println("fetchRuntimeJdk: downloading $url")
      val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
      val res = client.send(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.ofFile(archive.toPath()))
      if (res.statusCode() != 200) throw GradleException("download of $url answered ${res.statusCode()}")
    }
    val got = sha256Of(archive)
    if (got != spec.sha256) { archive.delete(); throw GradleException("SHA-256 of ${spec.archive} is $got, expected ${spec.sha256}: refusing to build with it.") }
    println("fetchRuntimeJdk: SHA-256 verified ($got)")
    if (spec.archive.endsWith(".zip")) unzipTo(archive, root)
    else {
      val p = ProcessBuilder("tar", "-xzf", archive.absolutePath, "-C", root.absolutePath).inheritIO().start()
      if (p.waitFor() != 0) throw GradleException("tar could not unpack ${spec.archive}")
    }
    archive.delete()
    val v = jdkReleaseVersion(File(home, "release")) ?: throw GradleException("no release file under $home after unpacking")
    println("fetchRuntimeJdk: ready, Java $v at $home")
  }
}

/** Fails when the runtime jpackage will bundle is older than MIN_SAFE_JDK (or cannot be read). */
fun requireSafeRuntime(releaseFile: File, what: String) {
  val v = jdkReleaseVersion(releaseFile) ?: throw GradleException("$what: cannot read the Java version from $releaseFile")
  if (!jdkAtLeast(v, MIN_SAFE_JDK)) throw GradleException("$what is Java $v; the installers need $MIN_SAFE_JDK or newer (JDK-8335662: older runtimes abort after an hour or so on Apple silicon).")
  println("$what: Java $v (minimum $MIN_SAFE_JDK): ok")
}

val checkRuntimeJdk = tasks.register("checkRuntimeJdk") {
  group = "trawl"
  description = "Fails the build when the Java runtime the installers will carry is older than $MIN_SAFE_JDK."
  if (pinnedJdkHome != null) dependsOn(fetchRuntimeJdk)
  val home = pinnedJdkHome ?: File(System.getProperty("java.home"))
  doLast { requireSafeRuntime(File(home, "release"), "Installer Java runtime ($home)") }
}

// After jlink and jpackage have run: the runtime that is really inside the app image must be the safe one (a stale or foreign javaHome would show here).
val checkPackagedRuntime = tasks.register("checkPackagedRuntime") {
  group = "trawl"
  description = "Reads the `release` file of the runtime image and of every app image jpackage made, and fails below $MIN_SAFE_JDK."
  val dirs = listOf(layout.buildDirectory.dir("compose/tmp/main/runtime"), layout.buildDirectory.dir("compose/binaries/main/app"))
  val buildDir = layout.buildDirectory.get().asFile
  doLast {
    var seen = 0
    for (d in dirs.map { it.get().asFile }.filter { it.isDirectory }) {
      for (f in d.walkTopDown().filter { it.isFile && it.name == "release" && jdkReleaseVersion(it) != null && ("runtime" in it.path) }) {
        requireSafeRuntime(f, "Packaged runtime (${f.relativeTo(buildDir)})"); seen++
      }
    }
    if (seen == 0) throw GradleException("checkPackagedRuntime found no runtime `release` file under build/compose: run it after createRuntimeImage or a package task")
  }
}

// No native library the app ships may hold Microsoft's telemetry client (ONNX Runtime 1.29 to 1.31, macOS and Linux): look for its strings, as
// ASCII and as UTF-16 (Windows), in every native file of every jar on the runtime classpath (litertlm-jvm carries all four systems' natives).
val NO_TELEMETRY_MARKERS = listOf("OneCollector", "events.data.microsoft.com", "Microsoft::Applications")
val checkNoTelemetryInNatives = tasks.register("checkNoTelemetryInNatives") {
  group = "trawl"
  description = "Fails when any native library on the runtime classpath contains the strings of the ONNX Runtime 1DS telemetry client."
  val runtime = configurations.named("runtimeClasspath")
  val stamp = layout.buildDirectory.file("checks/no-telemetry.ok")
  inputs.files(runtime); outputs.file(stamp)
  doLast {
    val patterns = NO_TELEMETRY_MARKERS.flatMap { m -> listOf(m.toByteArray(Charsets.US_ASCII), m.toByteArray(Charsets.UTF_16LE)) }
    val keep = patterns.maxOf { it.size }
    fun nativeName(n: String) = n.endsWith(".so") || n.endsWith(".dylib") || n.endsWith(".dll") || n.endsWith(".jnilib") || ".so." in n
    fun contains(buf: ByteArray, len: Int, p: ByteArray): Boolean {
      val last = len - p.size
      var i = 0
      while (i <= last) {
        if (buf[i] == p[0]) { var j = 1; while (j < p.size && buf[i + j] == p[j]) j++; if (j == p.size) return true }
        i++
      }
      return false
    }
    var files = 0
    val hits = ArrayList<String>()
    for (jar in runtime.get().files.filter { it.name.endsWith(".jar") }) {
      ZipFile(jar).use { z ->
        for (e in z.entries().asSequence().filter { !it.isDirectory && nativeName(it.name) }) {
          files++
          z.getInputStream(e).use { ins ->
            val buf = ByteArray((1 shl 20) + keep)
            var carry = 0
            while (true) {
              val n = ins.read(buf, carry, buf.size - carry)
              if (n < 0) break
              val len = carry + n
              for (p in patterns) if (contains(buf, len, p)) hits += "${jar.name}!${e.name} holds a telemetry-client string (${p.size} bytes: ${String(p, Charsets.ISO_8859_1).replace("\u0000", "")})"
              carry = minOf(keep - 1, len); System.arraycopy(buf, len - carry, buf, 0, carry)
            }
          }
        }
      }
    }
    if (hits.isNotEmpty()) throw GradleException("A native library holds the telemetry client (see NoTelemetry.kt):\n  " + hits.distinct().joinToString("\n  "))
    stamp.get().asFile.apply { parentFile.mkdirs(); writeText("scanned $files native files: no telemetry client\n") }
    println("checkNoTelemetryInNatives: $files native files scanned, none holds the telemetry client")
  }
}
tasks.test { dependsOn(checkNoTelemetryInNatives) }

// ---- Windows: the Visual C++ runtime piece ONNX Runtime needs ---------------------------------------------------------------------------------
// onnxruntime.dll (1.28, Windows) imports MSVCP140_1.dll. A clean Windows has no copy and the Temurin runtime does not carry one (it has msvcp140,
// vcruntime140 and vcruntime140_1), so on such a PC the library fails to load ("Can't find dependent libraries"), the Privacy pack reads "Not
// available on this computer" and nothing is scored (found by the Windows zero-network gate on a fresh Server 2022). The Windows build copies the
// DLL from the build machine's System32 (it has the Visual C++ 2015-2022 Redistributable; the build fails with a plain message if not) into the
// app's resources folder, and WindowsVcRuntime (privacy/PrivacyRuntime.kt) loads it before ONNX Runtime does. Microsoft allows the app-local
// copy of these redistributable DLLs. Other systems get nothing.
val vcRuntimeDll = "msvcp140_1.dll"
val vcRuntimeStage = layout.buildDirectory.dir("appres/windows")
val stageWindowsVcRuntime = tasks.register("stageWindowsVcRuntime") {
  group = "trawl"
  description = "Windows only: copies $vcRuntimeDll (Visual C++ runtime, needed by onnxruntime.dll) from System32 into the app resources."
  val stage = vcRuntimeStage
  val windows = System.getProperty("os.name").startsWith("Windows", true)
  outputs.dir(stage)
  doLast {
    val out = stage.get().asFile
    out.mkdirs()
    if (!windows) return@doLast
    val src = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32/$vcRuntimeDll")
    if (!src.isFile) throw GradleException("$vcRuntimeDll not found at $src: install the Visual C++ 2015-2022 Redistributable (x64) on this build machine; the Privacy pack cannot load on a clean Windows without it.")
    src.copyTo(File(out, vcRuntimeDll), overwrite = true)
    println("stageWindowsVcRuntime: $vcRuntimeDll ${src.length()} bytes, SHA-256 ${sha256Of(src)}")
  }
}

// macOS privacy purpose strings (Info.plist). Without them macOS asks "Trawl would like to access files in your Documents folder" with no
// reason shown, or refuses silently. These are the keys folder access can trigger: the three personal folders, USB/SD drives, network shares
// and cloud-storage folders (File Provider). Plain wording, true to the app: it reads on this Mac and nothing leaves it.
val MAC_PRIVACY_PURPOSE = "Trawl reads the files in this folder on this Mac to find them when you search. Nothing leaves your Mac."
val MAC_PRIVACY_KEYS = listOf(
  "NSDesktopFolderUsageDescription", "NSDocumentsFolderUsageDescription", "NSDownloadsFolderUsageDescription",
  "NSRemovableVolumesUsageDescription", "NSNetworkVolumesUsageDescription", "NSFileProviderDomainUsageDescription",
)
val macPrivacyPlistXml = MAC_PRIVACY_KEYS.joinToString("\n") { "<key>$it</key>\n<string>$MAC_PRIVACY_PURPOSE</string>" }

// The installers (.deb on Linux, .msi on Windows, .dmg on macOS), built with jpackage on the machine they are for. The model is
// NOT inside: a person points the app at trawl-models.zip (the signed bundle the phone uses) or leaves it in Downloads.
compose.desktop {
  application {
    mainClass = "trawl.desktop.MainKt"
    // The runtime inside every installer: the pinned Temurin from fetchRuntimeJdk above, never the machine's own JDK.
    pinnedJdkHome?.let { javaHome = it.absolutePath }
    jvmArgs += listOf("-Xmx1g", "-Dfile.encoding=UTF-8")
    // macOS only matters here: lets privacy/SecureWindow.kt reach the NSWindow to keep it out of screenshots. Harmless on other systems.
    jvmArgs += listOf("--add-opens=java.desktop/sun.awt=ALL-UNNAMED", "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED", "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED")
    // Linux only matters here: WmClass.kt names the X11 window class after the .deb's launcher entry (the dock icon). Harmless on other systems.
    jvmArgs += listOf("--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED")
    nativeDistributions {
      targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.Dmg)
      // The name people see: Trawl.app and Trawl-1.0.1.dmg on a Mac, the Start menu and the .msi on Windows. A per-OS `packageName` in the
      // macOS block only sets the menu-bar name (jpackage --mac-package-name), not the file names, so the top-level one has to be "Trawl".
      packageName = "Trawl"
      packageVersion = "1.0.1"
      appResourcesRootDir.set(layout.buildDirectory.dir("appres"))   // appres/windows/ lands in the app's resources folder on Windows only
      description = "Search your own files by meaning, on this computer. Nothing leaves it."
      vendor = "Crane AI Labs"
      // jdk.management holds com.sun.management.OperatingSystemMXBean (total RAM); without it the installed app read 0 GB.
      modules("java.sql", "java.management", "jdk.management", "java.desktop", "java.logging", "java.xml", "java.naming", "jdk.unsupported", "jdk.crypto.ec")
      // The mark, drawn from site/favicon.svg by scripts/make_desktop_icons.py: a PNG for the .deb (its menu entry and .desktop file),
      // an .ico for the .msi (the exe, its shortcuts, Add/Remove Programs) and an .icns for the macOS slot.
      // A .deb package name is lowercase by convention (trawl_1.0.1_amd64.deb); the menu entry still reads "Trawl" from the .desktop file.
      linux { packageName = "trawl"; menuGroup = "Utility"; shortcut = true; debMaintainer = "Crane AI Labs"; iconFile.set(project.file("packaging/trawl.png")) }
      windows { menuGroup = "Trawl"; shortcut = true; perUserInstall = true; upgradeUuid = "6f1d1f7e-3c0e-4a5e-9d57-2b1f5e0c7a11"; iconFile.set(project.file("packaging/trawl.ico")) }
      // Apple silicon only (litertlm-jvm and sqlite-bundled ship no Intel Mac natives). Unsigned: signing and notarisation need an Apple
      // Developer ID, which Crane AI Labs does not have, so Gatekeeper asks the person to allow it once (see internal/research/desktop-mac.md).
      macOS { iconFile.set(project.file("packaging/trawl.icns")); packageName = "Trawl"; dockName = "Trawl"; bundleID = "ai.craneailabs.trawl"; minimumSystemVersion = "13.0"
        infoPlist { extraKeysRawXml = macPrivacyPlistXml } }
    }
  }
}

// macOS: re-sign the app inside the finished .dmg, because jpackage adds Contents/app/.package after its own ad-hoc signature (see packaging/reseal-dmg.sh).
if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
  val resealDmg = tasks.register<Exec>("resealDmg") {
    val dmgDir = layout.buildDirectory.dir("compose/binaries/main/dmg")
    doFirst { commandLine("bash", project.file("packaging/reseal-dmg.sh").absolutePath, dmgDir.get().asFile.listFiles { f -> f.extension == "dmg" }!!.first().absolutePath) }
    commandLine("true")
  }
  tasks.matching { it.name == "packageDmg" }.configureEach { finalizedBy(resealDmg) }
}

// After packaging on macOS: every app image jpackage made must carry all the privacy purpose strings in its Info.plist, non-empty.
val checkMacPrivacyStrings = tasks.register("checkMacPrivacyStrings") {
  group = "trawl"
  description = "Fails when a built Trawl.app lacks any macOS privacy purpose string in Contents/Info.plist."
  val appDir = layout.buildDirectory.dir("compose/binaries/main/app")
  doLast {
    val plists = appDir.get().asFile.walkTopDown().maxDepth(4).filter { it.isFile && it.name == "Info.plist" && it.parentFile.name == "Contents" && it.parentFile.parentFile.extension == "app" }.toList()
    if (plists.isEmpty()) throw GradleException("checkMacPrivacyStrings found no Trawl.app/Contents/Info.plist under build/compose/binaries/main/app: run it after createDistributable or packageDmg")
    for (f in plists) {
      val xml = f.readText()
      for (k in MAC_PRIVACY_KEYS) {
        if (!Regex("<key>" + k + "</key>\\s*<string>[^<]{20,}</string>").containsMatchIn(xml)) throw GradleException("${f.parentFile.parentFile.name}: Info.plist has no purpose string for $k")
      }
      println("checkMacPrivacyStrings: ${f.parentFile.parentFile.name} carries all ${MAC_PRIVACY_KEYS.size} privacy purpose strings")
    }
  }
}
if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) tasks.matching { it.name == "createDistributable" || it.name == "packageDmg" }.configureEach { finalizedBy(checkMacPrivacyStrings) }

// Every task of the "compose desktop" group or of the plugin's task classes (checkRuntime is in no group, yet reads the runtime folder; runtime image, distributable, installers) first checks the runtime and the natives, and the packaging tasks check what ended up inside.
tasks.matching { it.group == "compose desktop" || it.javaClass.name.startsWith("org.jetbrains.compose.desktop.application.tasks") }.configureEach {
  dependsOn(checkRuntimeJdk, checkNoTelemetryInNatives, stageWindowsVcRuntime)
  if (name == "createRuntimeImage" || name == "createDistributable" || name.startsWith("package")) finalizedBy(checkPackagedRuntime)
}
// The plugin's resource-preparing task reads the whole appres/ folder, which holds the staged DLL: Gradle wants that order spelled out.
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(stageWindowsVcRuntime) }
