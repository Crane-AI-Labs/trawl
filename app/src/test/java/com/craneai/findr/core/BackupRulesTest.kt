package com.craneai.findr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Nothing of Findr's leaves the phone in a backup or a phone-to-phone transfer (the research notes, "Backups").
 * Android 12+: allowBackup="false" stops cloud backup but not device-to-device transfer, so the data extraction rules
 * must exclude every domain in both sections. Android 11 (minSdk 30) reads allowBackup alone.
 */
class BackupRulesTest {
  private val domains = setOf(
    "root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref",
  )

  private fun xml(path: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
    .newDocumentBuilder().parse(File(path)).documentElement

  @Test fun everyDomainIsExcludedFromCloudBackupAndDeviceTransfer() {
    val rules = xml("src/main/res/xml/data_extraction_rules.xml")
    for (section in listOf("cloud-backup", "device-transfer")) {
      val el = rules.getElementsByTagName(section).item(0) as Element? ?: error("no <$section>")
      assertEquals("<$section> must not include anything", 0, el.getElementsByTagName("include").length)
      val excludes = el.getElementsByTagName("exclude")
      val excluded = (0 until excludes.length).map { excludes.item(it) as Element }
      // No path: the whole domain (AOSP FullBackup.extractCanonicalFile treats a missing path as the domain root).
      excluded.forEach { assertEquals("<$section> excludes all of ${it.getAttribute("domain")}", "", it.getAttribute("path")) }
      assertEquals("<$section> domains", domains, excluded.map { it.getAttribute("domain") }.toSet())
    }
  }

  @Test fun theManifestTurnsBackupOffAndPointsAtTheRules() {
    val app = xml("src/main/AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
    val android = "http://schemas.android.com/apk/res/android"
    assertEquals("false", app.getAttributeNS(android, "allowBackup"))
    assertEquals("@xml/data_extraction_rules", app.getAttributeNS(android, "dataExtractionRules"))
    assertTrue("no backup agent", app.getAttributeNS(android, "backupAgent").isEmpty())
  }
}
