plugins {
  alias(libs.plugins.android.application)   // AGP 9: Kotlin is built in, no kotlin-android plugin
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.androidx.baselineprofile)
}

android {
  namespace = "com.craneai.findr"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.craneai.findr"
    minSdk = 30   // MediaStore generations (the change journal Findr's crawler relies on) start at Android 11
    targetSdk = 36   // compile against 37, but keep 36 runtime behaviour until it's tested
    versionCode = 2
    versionName = "1.0.1"
    // Compose UI tests (src/androidTest): screenshot-free checks of the Sunlight components on a phone.
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    // The ABI is per edition (productFlavors below): the full app is 64-bit only, Trawl Go is 32-bit only.
    // D4: prefer Findr's language-tuned model bundle when it is installed (`-PfindrFinetuned=true`). Off by default.
    buildConfigField("boolean", "FINDR_FINETUNED", providers.gradleProperty("findrFinetuned").orNull ?: "false")
    // Test builds only: `-PfindrProfile=lite|standard` fixes the memory profile in any build type (a release side build on a
    // test device), and `-PfindrVisionCpu=true` starts vision on the CPU (a device whose GPU path hangs). Unset, the app
    // chooses by RAM (MemoryProfile) and tries the GPU first, as every shipped build does.
    buildConfigField("boolean", "FINDR_VISION_CPU", providers.gradleProperty("findrVisionCpu").orNull ?: "false")
    // Test builds only: `-PfindrKnobs=true` lets a profiling build read engine/Tuning overrides from /data/local/tmp/dp-knobs.txt
    // (key=value lines), so the device-profile study can try settings without a rebuild each. Off in every shipped build.
    buildConfigField("boolean", "FINDR_KNOBS", providers.gradleProperty("findrKnobs").orNull ?: "false")
    // Test builds only: `-PfindrHeapLog=true` logs the Java heap (low / high / peak per 10 s) as "FindrHeap"; `-PheapBallastMb=128` holds
    // that many MB for the life of the process, so a phone whose Java heap limit is 256 MB behaves like a 128 MB one (diag/HeapProbe).
    buildConfigField("boolean", "FINDR_HEAPLOG", providers.gradleProperty("findrHeapLog").orNull ?: "false")
    buildConfigField("int", "HEAP_BALLAST_MB", providers.gradleProperty("heapBallastMb").orNull ?: "0")
    buildConfigField("String", "FINDR_PROFILE", "\"${providers.gradleProperty("findrProfile").orNull ?: ""}\"")
    // Detail logging (core/FindrLog): file names, paths and queries in logcat. Debug builds only, unless a release is
    // built with `-PfindrLogDetail=true` for a test phone (the smoke scripts read file names from the log). Never ship that.
    buildConfigField("boolean", "LOG_DETAIL", "false")
    // The quick-look pass's share of the time during the words pass (QuickLookPass.WORDS_DUTY). `-PquickWordsDuty=0.5` builds a
    // side build (a release one too) for D10's words-vs-quick-look measurement; the default is what ships.
    // Where the "Get the models" button sends the browser: this folder plus the bundle's file name (trawl-models.zip, or
    // trawl-models-lite.zip for a 2 GB phone). The folder is a GitHub release with its OWN tag, models-v1, apart from the app's v<version>
    // tags, so a model URL baked into an installed app never changes when the app is re-released (scripts/release.sh uploads there).
    // NOT live until the first release is published (waits for the maintainers' go). A test build points it at a laptop:
    // `-PmodelsBaseUrl=http://localhost:8765/` with `adb reverse tcp:8765 tcp:8765`.
    buildConfigField("String", "MODELS_BASE_URL", "\"${providers.gradleProperty("modelsBaseUrl").orNull ?: "https://github.com/Crane-AI-Labs/trawl/releases/download/models-v1/"}\"")
    buildConfigField("double", "QUICK_WORDS_DUTY", providers.gradleProperty("quickWordsDuty").orNull ?: "1.0")
  }

  // The publishing key is the maintainers' and never enters the repo: a release build is signed with it only
  // when the builder's ~/.gradle/gradle.properties names it (trawlKeystore, trawlKeystorePassword, trawlKeyAlias, trawlKeyPassword).
  // Without those, release builds are debug-signed test builds (adb install -r keeps working on test phones).
  // Only a publish build (-PtrawlPublish=true) uses it: everyday test builds stay on the debug key, so `adb install -r` keeps
  // upgrading the test phones' installs in place.
  val trawlKeystore = providers.gradleProperty("trawlKeystore").orNull
    ?.takeIf { providers.gradleProperty("trawlPublish").orNull == "true" }
  signingConfigs {
    if (trawlKeystore != null) {
      create("trawl") {
        storeFile = file(trawlKeystore)
        storePassword = providers.gradleProperty("trawlKeystorePassword").get()
        keyAlias = providers.gradleProperty("trawlKeyAlias").get()
        keyPassword = providers.gradleProperty("trawlKeyPassword").get()
      }
    }
  }
  // Two editions of one code base (src/main shared; src/full and src/go hold what differs):
  //  - full: Trawl as shipped. EmbeddingGemma on LiteRT-LM, quick look and privacy blur on ONNX Runtime, speech on sherpa-onnx.
  //    arm64-v8a only (LiteRT-LM ships no other ABI besides x86_64). Its behaviour and APK are unchanged by the go flavor.
  //  - go: Trawl Go, for 32-bit, low-memory Android Go phones (the itel A669W class: armeabi-v7a, 1 GB free). None of the
  //    three native stacks above has an armeabi-v7a build we can use, so Go has NO model code at all: it finds files by their
  //    names and the words in them (document text, and OCR of photos and screenshots), and says so. Its own package id, so it
  //    installs beside the full app (and so the full app's data and a Go phone's never mix).
  flavorDimensions += "edition"
  productFlavors {
    create("full") {
      dimension = "edition"
      buildConfigField("boolean", "EDITION_GO", "false")
      ndk { abiFilters += "arm64-v8a" }
    }
    create("go") {
      dimension = "edition"
      applicationIdSuffix = ".go"
      versionNameSuffix = "-go"
      buildConfigField("boolean", "EDITION_GO", "true")
      ndk { abiFilters += "armeabi-v7a" }
    }
  }
  buildTypes {
    debug {
      // Legacy, UNSIGNED model files (a loose .litertlm, or a bundle without a signed manifest) are accepted only here by default:
      // they are how benches and testers push models with adb. Release refuses them (the signed bundle is the only way a user
      // gets the model); a release test build opts in, loudly, with `-PallowUnsignedModels=true`.
      buildConfigField("boolean", "ALLOW_UNSIGNED_MODELS", providers.gradleProperty("allowUnsignedModels").orNull ?: "true")
      // `-PappIdSuffix=.export` installs a second debug app beside the tester's, with its own private files, so a bench
      // can load other models without touching the real app's model, index or settings.
      providers.gradleProperty("appIdSuffix").orNull?.let { applicationIdSuffix = it }
      // `-PfindrLogDetail=false` turns detail logging off in a debug build, to see what a user's build logs (and what a problem report holds).
      buildConfigField("boolean", "LOG_DETAIL", providers.gradleProperty("findrLogDetail").orNull ?: "true")
    }
    release {
      // The same side-by-side switch for a release build, so timings can be taken on what a user gets without touching their install.
      providers.gradleProperty("appIdSuffix").orNull?.let { applicationIdSuffix = it }
      // R8 on, resources shrunk, not debuggable: what a user gets, and what scroll smoothness has to be measured on
      // (a debug build runs Compose unoptimised, with no R8 and no baseline profile). Signed with the debug key for now,
      // so `adb install -r` upgrades in place and keeps the model and index; Play signing replaces this later.
      val allowUnsigned = providers.gradleProperty("allowUnsignedModels").orNull ?: "false"
      require(allowUnsigned == "true" || allowUnsigned == "false") { "allowUnsignedModels must be true or false, not '$allowUnsigned'" }
      if (allowUnsigned == "true") logger.warn("Findr: release build that ACCEPTS UNSIGNED model files. Test phones only, never ship it.")
      buildConfigField("boolean", "ALLOW_UNSIGNED_MODELS", allowUnsigned)
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.findByName("trawl") ?: signingConfigs.getByName("debug").also {
        if (providers.gradleProperty("trawlPublish").orNull == "true") error("trawlPublish=true but no publishing key: set trawlKeystore and its passwords")
      }
      // The same throwaway-clone switch as debug, so first-run can be measured on the build users get.
      providers.gradleProperty("appIdSuffix").orNull?.let { applicationIdSuffix = it }
      val logDetail = providers.gradleProperty("findrLogDetail").orNull ?: "false"
      require(logDetail == "true" || logDetail == "false") { "findrLogDetail must be true or false, not '$logDetail'" }
      if (logDetail == "true") logger.warn("Findr: release build WITH detail logging (file names in logcat). Test phones only, never ship it.")
      buildConfigField("boolean", "LOG_DETAIL", logDetail)
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  // The launcher shortcut names its target package in XML, and that has to be a literal (a @string reference is not resolved there).
  // The package depends on the edition (".go" for Trawl Go) and on a side-by-side build's suffix (-PappIdSuffix), so each variant
  // gets a generated copy of shortcuts.xml with its own package, which overrides main's. The full app without a suffix needs none.
  // A side build is labelled with its suffix ("Trawl (quick)"), so a test phone's launcher shows which clone is which instead of
  // several identical icons; Trawl Go's launcher label is "Trawl Go". Only the launcher label changes (launcher_label); app_name,
  // which the app's own copy uses, stays the plain name.
  val sideSuffix = providers.gradleProperty("appIdSuffix").orNull
  val appName = Regex("<string name=\"app_name\"[^>]*>([^<]+)</string>").find(file("src/main/res/values/strings.xml").readText())
    ?.groupValues?.get(1) ?: "app"
  for (edition in listOf("full", "go")) for (type in listOf("debug", "release")) {
    val goEdition = edition == "go"
    if (sideSuffix == null && !goEdition) continue
    val pkg = "com.craneai.findr" + (if (goEdition) ".go" else "") + (sideSuffix ?: "")
    val label = (if (goEdition) "$appName Go" else appName) + (sideSuffix?.let { " (${it.removePrefix(".")})" } ?: "")
    val gen = layout.buildDirectory.dir("generated/shortcuts-$edition-$type").get().asFile
    File(gen, "xml").mkdirs()
    File(gen, "xml/shortcuts.xml").writeText(
      file("src/main/res/xml/shortcuts.xml").readText().replace("\"com.craneai.findr\"", "\"$pkg\""),
    )
    File(gen, "values").mkdirs()
    File(gen, "values/side_build_label.xml").writeText(
      "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n  <string name=\"launcher_label\" translatable=\"false\">$label</string>\n</resources>\n",
    )
    sourceSets.maybeCreate(edition + type.replaceFirstChar { it.uppercase() }).res.srcDir(gen)
  }
  packaging {
    jniLibs {
      useLegacyPackaging = true
      // sherpa-onnx's AAR carries a stand-alone libonnxruntime.so for x86 only (on arm64 its ORT is static inside
      // libsherpa-onnx-jni.so), so the Java ORT clashes there and nowhere else. Findr is arm64-only, so x86 is dropped
      // anyway. Kept narrow on purpose: an arm64 clash would mix ORT versions in one process and must fail the build.
      pickFirsts += "lib/x86/libonnxruntime.so"
    }
  }
  // Translation hygiene (docs: the translation notes). Text in layouts and malformed format strings fail the build; a language
  // that is only partly translated is fine (the missing strings fall back to English on purpose), so MissingTranslation is
  // only information. TranslationsTest and HardcodedTextTest check the same rules on the JVM.
  lint {
    error += setOf("HardcodedText", "StringFormatInvalid", "StringFormatMatches", "StringFormatCount", "ExtraTranslation")
    informational += "MissingTranslation"
  }
  testOptions {
    unitTests.isReturnDefaultValues = true
    // The app-path benchmark harness (src/test/.../harness) loads tens of thousands of vectors; the 512 MB default is too small.
    unitTests.all { it.maxHeapSize = "4g" }
  }
}

// Startup profile: generated by :baselineprofile (a Macrobenchmark run on a real phone: app start, onboarding, the search grid) and
// compiled into the release APK so a fresh install starts ahead-of-time compiled instead of interpreting its first composition.
// Regenerate with scripts/gen-baseline-profile.sh on one attached phone; a normal build never needs a phone.
baselineProfile {
  automaticGenerationDuringBuild = false
  saveInSrc = true       // checked in, under app/src/main/generated/baselineProfiles: the one profile both editions use (a variant-specific folder is not read by a flavored variant)
  mergeIntoMain = true
}

dependencies {
  baselineProfile(project(":baselineprofile"))
  implementation(project(":core"))                  // the shared engine (Kotlin Multiplatform: android + jvm), also used by desktop/
  implementation(libs.androidx.profileinstaller)   // applies the profile on a sideloaded install (there is no Play to do it)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.splashscreen)
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.biometric)
  implementation(libs.androidx.fragment)
  // The three native stacks below are the full edition's only: Trawl Go (armeabi-v7a) has none of them, and its code (src/go) never
  // names their classes. EmbeddingGemma on LiteRT-LM:
  "fullImplementation"(libs.litertlm)
  // sherpa-onnx (ONNX Runtime linked in): speech to words, VAD and sound labels. scripts/fetch-asr.sh puts it in app/libs.
  "fullImplementation"(fileTree("libs") { include("*.aar") })
  // The quick-look pass (TinyCLIP int8 on the CPU) runs on a slim ONNX Runtime 1.31 (operators for those two graphs only, no
  // telemetry client): app/libs/onnxruntime-slim.aar, built by scripts/ort-slim/build.sh. The stock AAR costs +12.9 MB compressed.
  // It coexists with sherpa's static ORT inside libsherpa-onnx-jni.so (both are picked up by the fileTree above).
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.material.icons)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.work)
  implementation(libs.androidx.sqlite.bundled)
  implementation(libs.mlkit.text)
  implementation(libs.pdfbox.android)
  implementation(libs.coil.compose)
  implementation(libs.coil.video)
  debugImplementation(libs.androidx.ui.tooling)
  // Security bench only (bench/SecurityBenchActivity, the research notes): what SQLCipher would cost. Not a
  // Findr dependency; added to a debug build with -PbenchSqlcipher=true.
  if (providers.gradleProperty("benchSqlcipher").orNull == "true") debugImplementation("net.zetetic:sqlcipher-android:4.19.1@aar")
  testImplementation(libs.junit)
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
  // Compose UI tests on a phone (connectedDebugAndroidTest, with -PappIdSuffix so the tester's app is never touched).
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  debugImplementation(libs.androidx.ui.test.manifest)
  // The pull parser Android ships (android.util.Xml is a stub on the JVM), so the office-format tests run off-device.
  testImplementation("net.sf.kxml:kxml2:2.3.0")
  // android.jar's org.json is a stub on the JVM; the settings migration test reads and writes the real JSON.
  testImplementation("org.json:json:20180813")
  // The same bundled SQLite (with FTS5) on the JVM, so index and search tests run the real driver without a phone.
  testImplementation(libs.androidx.sqlite.bundled.jvm)
}

// The JVM driver replaces the Android one on the unit-test classpath (both define BundledSQLiteDriver).
configurations.matching { it.name.contains("UnitTest") }.configureEach {
  exclude(group = "androidx.sqlite", module = "sqlite-bundled-android")
}

// Two editions, one set of scripts. About twenty scripts (and the release checks) still run `:app:assembleRelease` / `assembleDebug`
// and read app/build/outputs/apk/{release,debug}/app-{release,debug}.apk. Those names keep meaning the FULL edition, exactly as
// before the flavors: the unflavored tasks build only the full variant and leave a copy at the old path. Trawl Go is built
// explicitly (`:app:assembleGoRelease`, output apk/go/release/app-go-release.apk) and never by accident.
afterEvaluate {
  for (type in listOf("Release", "Debug")) {
    val lower = type.lowercase()
    val compat = tasks.register<Copy>("copyFull${type}ApkToLegacyPath") {
      from(layout.buildDirectory.dir("outputs/apk/full/$lower")) { include("*.apk") ; rename { "app-$lower.apk" } }
      into(layout.buildDirectory.dir("outputs/apk/$lower"))
    }
    tasks.named("assemble$type") {
      setDependsOn(listOf("assembleFull$type"))
      finalizedBy(compat)
    }
    compat.configure { dependsOn("assembleFull$type") }
    tasks.findByName("bundle$type")?.let { b -> b.setDependsOn(listOf("bundleFull$type")) }
  }
}
