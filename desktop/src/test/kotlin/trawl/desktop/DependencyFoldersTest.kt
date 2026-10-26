package trawl.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The dependency, build and cache folders that are never read, and the folders that only look like them and are. */
class DependencyFoldersTest {
  private fun tmp() = Files.createTempDirectory("trawl-dep").toFile().canonicalFile
  private fun dir(root: File, rel: String) = File(root, rel).apply { mkdirs() }
  private fun file(root: File, rel: String, text: String = "x") = File(root, rel).apply { parentFile.mkdirs(); writeText(text) }
  private fun skipped(root: File, rel: String) = DependencyFolders.isSkipped(File(root, rel).toPath())

  @Test fun foldersOnlyBuildToolsWriteAreSkippedByNameWhereverTheyAre() {
    val r = tmp()
    for (n in listOf("node_modules", "bower_components", "__pycache__", "DerivedData", ".gradle")) {
      dir(r, "a/b/$n"); assertTrue(skipped(r, "a/b/$n"), n)
    }
    assertFalse(skipped(r, "a/b"), "a folder with another name is read")
    assertFalse(DependencyFolders.isSkipped(r.toPath().root), "the file system root has no name")
    r.deleteRecursively()
  }

  @Test fun aVirtualEnvironmentIsSkippedOnlyWhenItHoldsPyvenvCfg() {
    val r = tmp()
    file(r, "proj/.venv/pyvenv.cfg"); file(r, "proj/venv/pyvenv.cfg")
    dir(r, "holiday/venv"); file(r, "holiday/venv/trip.txt")   // a trip to Venice, not an environment
    assertTrue(skipped(r, "proj/.venv")); assertTrue(skipped(r, "proj/venv"))
    assertFalse(skipped(r, "holiday/venv"))
    r.deleteRecursively()
  }

  @Test fun podsNeedAPodfileBesideThem() {
    val r = tmp()
    dir(r, "ios/Pods"); file(r, "ios/Podfile")
    dir(r, "other/Pods")
    assertTrue(skipped(r, "ios/Pods")); assertFalse(skipped(r, "other/Pods"))
    r.deleteRecursively()
  }

  @Test fun targetNeedsAPomOrACargoFileBesideIt() {
    val r = tmp()
    dir(r, "java/target"); file(r, "java/pom.xml")
    dir(r, "rust/target"); file(r, "rust/Cargo.toml")
    dir(r, "photos/target"); file(r, "photos/a.jpg")   // "target" in a photo library
    assertTrue(skipped(r, "java/target")); assertTrue(skipped(r, "rust/target")); assertFalse(skipped(r, "photos/target"))
    r.deleteRecursively()
  }

  @Test fun buildNeedsAGradleOrPackageFileBesideIt() {
    val r = tmp()
    dir(r, "g/build"); file(r, "g/build.gradle")
    dir(r, "k/build"); file(r, "k/build.gradle.kts")
    dir(r, "n/build"); file(r, "n/package.json")
    dir(r, "wedding/build"); file(r, "wedding/build/a.txt")   // a folder named build with nothing that makes it output
    assertTrue(skipped(r, "g/build")); assertTrue(skipped(r, "k/build")); assertTrue(skipped(r, "n/build"))
    assertFalse(skipped(r, "wedding/build"))
    r.deleteRecursively()
  }

  @Test fun theChosenFolderItselfIsNeverSkippedButAnythingInsideASkippedOneIs() {
    val r = tmp()
    val nm = dir(r, "node_modules")
    assertFalse(DependencyFolders.insideSkipped(nm.toPath(), nm.toPath()), "the person chose it")
    assertFalse(DependencyFolders.insideSkipped(r.toPath(), r.toPath()))
    val deep = dir(r, "p/node_modules/x/y/z")
    assertTrue(DependencyFolders.insideSkipped(r.toPath(), deep.toPath()))
    assertTrue(DependencyFolders.insideSkipped(r.toPath(), File(r, "p/node_modules").toPath()))
    assertFalse(DependencyFolders.insideSkipped(r.toPath(), File(r, "p").toPath()))
    assertFalse(DependencyFolders.insideSkipped(r.toPath(), File("/elsewhere/node_modules").toPath()), "not under the root: not this check's business")
    r.deleteRecursively()
  }

  @Test fun theCrawlDoesNotEnterThemAndTheirAbsenceIsNotAnUnreadableFolder() {
    val r = tmp()
    file(r, "p/package.json", "{}"); file(r, "p/src/main.ts", "export const a = 1")
    file(r, "p/node_modules/lib/index.ts", "export {}"); file(r, "p/node_modules/lib/readme.txt", "docs of a package")
    file(r, "p/build/out.txt", "build output")                  // package.json beside it: output
    file(r, "p/__pycache__/m.txt", "cache")
    file(r, "trip/build/notes.txt", "kept")                     // nothing beside it: a person's folder
    file(r, "notes/a.txt", "a note")
    file(r, "Videos/camera.ts", "not a stream")                  // TypeScript or junk, not a video
    val seen = ArrayList<String>()
    val dirsBefore = Crawler.Counters.dirs.sum()
    val result = Crawler.walk(r.toPath(), r.toPath(), true) { seen += it.rel + it.name }
    assertEquals(setOf("p/package.json", "notes/a.txt", "trip/build/notes.txt"), seen.toSet(), "$seen")
    assertTrue(result.complete, "a folder left out on purpose is not a folder that could not be read")
    assertEquals(emptyList(), result.unreadable.map { it.path })
    // Entered: root, p, p/src, trip, trip/build, notes, Videos. Not node_modules (2 below it), p/build or p/__pycache__.
    assertEquals(7L, Crawler.Counters.dirs.sum() - dirsBefore)
    r.deleteRecursively()
  }

  @Test fun aWalkAskedForAPlaceInsideASkippedFolderReadsNothingAndCountsNoWalk() {
    val r = tmp()
    file(r, "p/node_modules/lib/a.txt", "a note")
    val walks = Crawler.Counters.walks.sum()
    val seen = ArrayList<String>()
    val result = Crawler.walk(r.toPath(), File(r, "p/node_modules/lib").toPath(), true) { seen += it.name }
    assertEquals(emptyList(), seen); assertTrue(result.complete)
    assertEquals(walks, Crawler.Counters.walks.sum())
    r.deleteRecursively()
  }
}
