// :core, Trawl's shared engine as a Kotlin Multiplatform library with two targets: android (used by :app) and jvm (used by desktop/).
// Package names are the app's own (com.craneai.findr.*), so nothing in :app changes its imports when a file moves here.
//
// Source sets:
//   jvmCommonMain   all the shared code: plain Kotlin on the JDK (java.io, java.util, org.json), compiled once per target. It holds no
//                   expect/actual on purpose: what the host must supply is an interface the host sets at start-up (AssetSource, LogSink,
//                   DiagPlatform, BuildFlags), so the desktop build (../desktop) can compile this one tree directly without Android's toolchain.
//   jvmMain         only the JVM-side dependencies (the real org.json and kxml2); the Android target gets those from android.jar.
//   commonMain      the dependencies every target shares (coroutines, androidx.sqlite).
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
  android {
    namespace = "com.craneai.findr.core"
    compileSdk = 37
    minSdk = 30
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    withHostTest {
      isReturnDefaultValues = true
    }
  }
  jvm {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }

  @OptIn(ExperimentalKotlinGradlePluginApi::class)
  applyDefaultHierarchyTemplate {
    common {
      group("jvmCommon") {
        // withAndroidTarget() does not match AGP's Kotlin-multiplatform android target, so name its compilations by hand.
        withCompilations { it.target.name == "android" }
        withJvm()
      }
    }
  }

  sourceSets {
    commonMain.dependencies {
      api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
      api(libs.androidx.sqlite.bundled)   // the index and the vector store speak androidx.sqlite (Db, IndexStore)
    }
    named("jvmCommonMain") {
      dependencies {
        // android.jar carries org.json on a phone; the jvm target brings the real library (jvmMain below).
        compileOnly("org.json:json:20251224")
        // ... and org.xmlpull.v1 (the pull-parser API), which android.jar also carries; jvmMain brings the kxml2 implementation.
        compileOnly("net.sf.kxml:kxml2:2.3.0")
      }
    }
    jvmMain.dependencies {
      implementation("org.json:json:20251224")
      implementation("net.sf.kxml:kxml2:2.3.0")
    }
    // Tests in jvmCommonTest run twice, once per target (jvmTest and the Android host test), like the code they cover.
    named("jvmCommonTest") {
      dependencies {
        implementation(libs.junit)
      }
    }
    jvmTest.dependencies {
      implementation("org.json:json:20251224")
      implementation(libs.androidx.sqlite.bundled.jvm)
    }
    getByName("androidHostTest").dependencies {
      // android.jar's org.json is a stub on the JVM; the tests read and write the real one (as :app's unit tests do).
      implementation("org.json:json:20180813")
      implementation("net.sf.kxml:kxml2:2.3.0")
      implementation(libs.androidx.sqlite.bundled.jvm)
    }
  }
}

// The JVM SQLite driver replaces the Android one on the host-test classpath (both define BundledSQLiteDriver).
configurations.matching { it.name.contains("HostTest") }.configureEach {
  exclude(group = "androidx.sqlite", module = "sqlite-bundled-android")
}
tasks.withType<Test>().configureEach {
  maxHeapSize = "4g"
}

// The index kill harness (core/src/jvmTest/.../harness/KillHarness.kt): ./gradlew :core:killHarness -Pkh="kill /mnt/data/kh/run1 300 400 6000".
// Not part of any test task. It spawns child JVMs that write the index like the app and SIGKILLs them at random moments.
// Prints the harness's runtime classpath, to run it by hand on another machine (an aarch64 board, like a phone).
tasks.register("killHarnessClasspath") {
  val jvm = kotlin.jvm()
  val test = jvm.compilations.getByName("test")
  val main = jvm.compilations.getByName("main")
  dependsOn(test.compileTaskProvider)
  val cp = files(test.output.allOutputs, main.output.allOutputs, test.runtimeDependencyFiles)
  doLast { println("HARNESS-CP " + cp.files.joinToString(":")) }
}

tasks.register<JavaExec>("killHarness") {
  val jvm = kotlin.jvm()
  val test = jvm.compilations.getByName("test")
  val main = jvm.compilations.getByName("main")
  dependsOn(test.compileTaskProvider)
  classpath = files(test.output.allOutputs, main.output.allOutputs, test.runtimeDependencyFiles)
  mainClass.set("com.craneai.findr.harness.KillHarnessKt")
  jvmArgs("-Xmx1g")
  args(providers.gradleProperty("kh").orElse("").get().split(" ").filter { it.isNotBlank() })
}
