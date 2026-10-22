plugins {
  alias(libs.plugins.android.test)   // AGP 9: Kotlin is built in, no kotlin-android plugin
  alias(libs.plugins.androidx.baselineprofile)
}

android {
  namespace = "com.craneai.findr.baselineprofile"
  compileSdk = 37

  defaultConfig {
    minSdk = 30
    targetSdk = 36
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    // The app the journey drives: the real package, or a side-by-side clone (-PappIdSuffix=.name) so a phone's own Findr is never cleared.
    // The app has two editions (full, go); the profile journey drives the full one.
    missingDimensionStrategy("edition", "full")
    testInstrumentationRunnerArguments["targetAppId"] = "com.craneai.findr" + (providers.gradleProperty("appIdSuffix").orNull ?: "")
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  targetProjectPath = ":app"
}

// Real, attached phones only (this repo has no Gradle managed devices). One phone at a time: name it with ANDROID_SERIAL and
// run under scripts/phone-lock.sh (scripts/gen-baseline-profile.sh does both).
baselineProfile {
  useConnectedDevices = true
}

dependencies {
  implementation(libs.androidx.test.ext.junit)
  implementation(libs.androidx.uiautomator)
  implementation(libs.androidx.benchmark.macro.junit4)
  implementation(libs.junit)
}
