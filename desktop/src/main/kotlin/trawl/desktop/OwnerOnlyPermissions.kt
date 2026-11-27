package trawl.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Enforces owner-only POSIX permissions (`0700` on directories, `0600` on sensitive index/config/privacy files) on
 * macOS and Linux so other local user accounts on shared machines cannot read Trawl's index, vectors, logs, or settings.
 * Silently no-ops on Windows or filesystems without POSIX permission support.
 */
object OwnerOnlyPermissions {
  val DIR_MODE: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwx------")
  val FILE_MODE: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")

  fun hardenDir(dir: File): Boolean {
    if (!dir.isDirectory) return false
    return runCatching {
      val p = dir.toPath()
      if (!Files.isSymbolicLink(p)) Files.setPosixFilePermissions(p, DIR_MODE)
      true
    }.getOrDefault(false)
  }

  fun hardenFile(file: File): Boolean {
    if (!file.isFile) return false
    return runCatching {
      val p = file.toPath()
      if (!Files.isSymbolicLink(p)) Files.setPosixFilePermissions(p, FILE_MODE)
      true
    }.getOrDefault(false)
  }

  /** Hardens [dataDir], its private subdirectories (`index/`, `logs/`, `tmp/`), and all sensitive database/vector/config files. */
  fun hardenDataDir(dataDir: File) {
    if (!dataDir.exists()) dataDir.mkdirs()
    hardenDir(dataDir)
    for (sub in listOf("index", "logs", "tmp")) {
      val d = File(dataDir, sub)
      if (d.isDirectory) hardenDir(d)
    }
    for (name in listOf("settings.json", "privacy.json", "privacy.json.bak", "shell.json", "vectors.bin")) {
      hardenFile(File(dataDir, name))
    }
    val indexDir = File(dataDir, "index")
    if (indexDir.isDirectory) {
      indexDir.listFiles()?.forEach { child ->
        if (child.isFile) hardenFile(child)
      }
    }
    val logsDir = File(dataDir, "logs")
    if (logsDir.isDirectory) {
      logsDir.listFiles()?.forEach { child ->
        if (child.isFile) hardenFile(child)
      }
    }
  }
}
