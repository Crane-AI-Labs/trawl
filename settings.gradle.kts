pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "Findr"
include(":app")
include(":core")
include(":baselineprofile")

// Google Play Asset Delivery on-demand packs: included only for Play AAB builds (-PtrawlPlayAssetDelivery=true)
// so sideload APK builds stay completely lean with zero Play Core dependency.
if (providers.gradleProperty("trawlPlayAssetDelivery").orNull == "true") {
  include(":asset-packs:pack_speech")
  include(":asset-packs:pack_quicklook")
  include(":asset-packs:pack_privacy")
  include(":asset-packs:pack_speech_lg")
  include(":asset-packs:pack_speech_nyn")
  include(":asset-packs:pack_speech_ach")
  include(":asset-packs:pack_speech_sw")
  include(":asset-packs:pack_speech_xog")
}
