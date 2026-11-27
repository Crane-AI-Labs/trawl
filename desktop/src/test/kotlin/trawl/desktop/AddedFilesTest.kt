package trawl.desktop

import com.craneai.findr.core.KindFilter
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddedFilesTest {
  private fun waitFor(what: String, timeoutMs: Long = 20_000L, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
      if (cond()) return
      Thread.sleep(40)
    }
    throw AssertionError("Timed out waiting for: $what")
  }

  @Test fun addFilesIndexesOnlyPickedFilesSurvivesRescanAndNeverDeletesDiskOnRemove() {
    val data = Files.createTempDirectory("trawl-added-files-data").toFile()
    val looseDir = Files.createTempDirectory("trawl-added-files-loose").toFile()
    val otherRoot = Files.createTempDirectory("trawl-added-files-root").toFile()
    val fileA = File(looseDir, "alpha-contract.txt").apply { writeText("alpha contract clause") }
    val fileB = File(looseDir, "beta-invoice.txt").apply { writeText("beta invoice summary") }
    val fileC = File(looseDir, "gamma-sibling.txt").apply { writeText("gamma sibling secret") }
    val rootDoc = File(otherRoot, "root-note.txt").apply { writeText("folder root note") }

    val c = AppController(
      dataDir = data,
      userFolders = Platform.UserFolders(data, data, data),
      logSink = {},
    )
    try {
      // Add a separate folder root so dropOutsideRoots() actively checks root prefixes
      c.addFolder(otherRoot)
      waitFor("rootDoc indexed") { c.service.recent(KindFilter.ALL, 20).any { it.name == rootDoc.name } }

      // Add ONLY fileA and fileB from looseDir (leaving sibling fileC unindexed)
      c.addFiles(listOf(fileA, fileB, looseDir))   // directories must be ignored cleanly
      assertEquals(
        listOf(fileA.toPath().toAbsolutePath().normalize().toString(), fileB.toPath().toAbsolutePath().normalize().toString()),
        c.settings.value.addedFiles,
      )
      waitFor("fileA and fileB indexed") {
        val names = c.service.recent(KindFilter.ALL, 20).map { it.name }.toSet()
        fileA.name in names && fileB.name in names
      }
      val namesAfterAdd = c.service.recent(KindFilter.ALL, 20).map { it.name }.toSet()
      assertTrue(fileA.name in namesAfterAdd)
      assertTrue(fileB.name in namesAfterAdd)
      assertFalse(fileC.name in namesAfterAdd, "sibling fileC must NOT be crawled when adding individual files")

      // Both added files survive rescan AND removing the unrelated folder root (dropOutsideRoots)
      c.rescan()
      c.removeFolder(otherRoot.absoluteFile.normalize().path)
      waitFor("rootDoc dropped while fileA and fileB survive") {
        val names = c.service.recent(KindFilter.ALL, 20).map { it.name }.toSet()
        rootDoc.name !in names && fileA.name in names && fileB.name in names
      }
      assertFalse(fileC.name in c.service.recent(KindFilter.ALL, 20).map { it.name })

      // Remove fileA from index: leaves SQLite & addedFiles, adds to excludedFiles, NEVER touches disk, leaves fileB intact
      val indexedA = c.service.recent(KindFilter.ALL, 20).first { it.name == fileA.name }
      c.removeFromIndex(listOf(indexedA))
      waitFor("fileA removed from index") {
        val names = c.service.recent(KindFilter.ALL, 20).map { it.name }.toSet()
        fileA.name !in names && fileB.name in names
      }
      assertTrue(fileA.isFile, "removeFromIndex must NEVER delete user file on disk")
      assertTrue(c.settings.value.addedFiles.none { it.endsWith(fileA.name) })
      assertTrue(c.settings.value.excludedFiles.any { it.endsWith(fileA.name) })

      // Re-adding fileA clears it from excludedFiles and indexes it again cleanly
      c.addFiles(listOf(fileA))
      assertTrue(c.settings.value.excludedFiles.none { it.endsWith(fileA.name) })
      waitFor("fileA re-indexed after re-add") {
        c.service.recent(KindFilter.ALL, 20).any { it.name == fileA.name }
      }

      // SettingsStore persists addedFiles cleanly across reload
      val reloaded = SettingsStore(data).load()
      assertTrue(reloaded.addedFiles.any { it.endsWith(fileA.name) })
      assertTrue(reloaded.addedFiles.any { it.endsWith(fileB.name) })
    } finally {
      c.close()
      data.deleteRecursively()
      looseDir.deleteRecursively()
      otherRoot.deleteRecursively()
    }
  }
}
