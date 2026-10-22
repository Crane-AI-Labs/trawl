package com.craneai.findr.index

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Users keep their index across app updates, so a layout change must come with a migration
 * (kotlin-fhir-engine's discipline: frozen schemas plus a migration test, the research notes).
 *  - The current layout is frozen in src/test/resources/schema/v<VERSION>.sql: changing Schema.CREATE without bumping
 *    VERSION fails here.
 *  - Every older frozen layout is upgraded through Schema.MIGRATIONS and must end up exactly like a fresh index, with
 *    its rows kept.
 */
class SchemaMigrationTest {
  private lateinit var dir: File
  private val snapshots = File("src/test/resources/schema")

  @Before fun setUp() { dir = Files.createTempDirectory("findr-schema").toFile() }
  @After fun tearDown() { dir.deleteRecursively() }

  /** The layout as SQLite stores it, without FTS5's own shadow tables. */
  private fun layout(store: IndexStore): String = store.read { db ->
    val out = ArrayList<String>()
    db.query(
      "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'fts_%' ORDER BY type, name",
    ) { out += it.getText(0).trim() }
    out.joinToString(";\n\n") + ";\n"
  }

  @Test fun theCurrentLayoutIsFrozen() {
    val store = IndexStore.open(File(dir, "fresh"), log = {})
    val current = layout(store)
    store.close()
    val frozen = File(snapshots, "v${Schema.VERSION}.sql")
    if (!frozen.exists()) {
      frozen.parentFile.mkdirs()
      frozen.writeText(current)
      fail("wrote ${frozen.path}: commit it and run again")
    }
    assertEquals("Schema.CREATE changed: bump Schema.VERSION, add a step to Schema.MIGRATIONS and freeze the new layout", frozen.readText(), current)
  }

  @Test fun migrationsCoverEveryVersion() = assertEquals(Schema.VERSION - 1, Schema.MIGRATIONS.size.toLong())

  @Test fun everyOlderLayoutUpgradesToTheCurrentOne() {
    val fresh = IndexStore.open(File(dir, "fresh"), log = {})
    val target = layout(fresh)
    fresh.close()
    for (v in 1 until Schema.VERSION) {
      val frozen = File(snapshots, "v$v.sql")
      if (!frozen.exists()) fail("missing frozen layout ${frozen.path}")
      val sub = File(dir, "v$v").apply { mkdirs() }
      val db = Db(BundledSQLiteDriver().open(File(sub, IndexStore.FILE_NAME).path))
      // Statements are separated by a blank line (layout() above); a trigger body has ";" inside, so do not split on that.
      frozen.readText().split(";\n\n").map { it.trim().removeSuffix(";") }.filter { it.isNotEmpty() }
        .sortedBy { if (it.contains(" TABLE ")) 0 else 1 }   // layout() lists indexes before the tables they sit on
        .forEach { db.exec(it) }
      db.exec("INSERT INTO meta(key, value) VALUES('${Schema.Meta.SCHEMA}', '$v')")
      db.exec("INSERT INTO meta(key, value) VALUES('kept', 'yes')")
      db.exec("PRAGMA user_version = $v")
      db.close()
      val upgraded = IndexStore.open(sub, log = {})
      assertEquals("v$v did not upgrade to the current layout", target, layout(upgraded))
      assertEquals("yes", upgraded.meta("kept"))
      assertEquals(Schema.VERSION.toString(), upgraded.meta(Schema.Meta.SCHEMA))
      upgraded.close()
    }
  }
}
