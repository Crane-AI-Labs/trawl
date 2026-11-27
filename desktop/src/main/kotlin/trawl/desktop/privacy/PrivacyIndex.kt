package trawl.desktop.privacy

import com.craneai.findr.core.FileKind
import com.craneai.findr.core.IndexedFile
import com.craneai.findr.index.IndexStore
import com.craneai.findr.index.PrivacyRules
import trawl.desktop.IndexAccess

/**
 * The part of the index the privacy code needs, as a narrow interface. `IndexService` owns the one `IndexStore` and keeps it private,
 * so everything here (marks, the scoring pass) talks to it through this seam, and the tests use a small in-memory stand-in.
 */
interface PrivacyIndex {
  /** Brings every row in line with [rules] (after a rebuild, or when a private folder changes). Returns how many rows changed. */
  fun applyRules(rules: PrivacyRules): Int

  /** Marks or unmarks one file by hand. The rules now in force, or null when the file is gone. */
  fun setMarked(fileId: Long, on: Boolean): PrivacyRules?

  /** Rules the crawler taught itself (a marked file moved or renamed) that are not saved yet; null when nothing is new. */
  fun takeLearnedRules(): PrivacyRules?

  fun isSensitive(fileId: Long): Boolean
  fun sensitiveCount(): Int

  /** Pictures not scored yet, newest first. */
  fun nextForPrivateScore(limit: Int, kinds: Set<FileKind>): List<IndexedFile>

  /** A picture's score, or null for "tried, cannot be read". */
  fun saveLooksPrivate(fileId: Long, score: Float?)

  /** A different model clears every score so the pictures are scored again. */
  fun ensurePrivateModel(model: String): Boolean

  /** The Privacy pack was removed: no stale score may linger. */
  fun clearLooksPrivate(): Int

  /** Scored out of scoreable. */
  fun privateScoreCounts(): Pair<Int, Int>
}

/** The real thing: [IndexStore]'s own privacy API, unchanged. */
class StorePrivacyIndex(private val store: IndexStore, private val access: IndexAccess = IndexAccess(), private val available: () -> Boolean = { true }) : PrivacyIndex {
  private fun <Result> read(unavailable: Result, block: () -> Result): Result = access.read(unavailable) { if (available()) block() else unavailable }
  override fun applyRules(rules: PrivacyRules) = read(0) { store.applyRules(rules) }
  override fun setMarked(fileId: Long, on: Boolean) = read(null) { store.setMarked(fileId, on) }
  override fun takeLearnedRules() = read(null) { store.takeLearnedRules() }
  override fun isSensitive(fileId: Long) = read(true) { store.isSensitive(fileId) }
  override fun sensitiveCount() = read(0) { store.sensitiveCount() }
  override fun nextForPrivateScore(limit: Int, kinds: Set<FileKind>) = read(emptyList()) { store.nextForPrivateScore(limit, kinds) }
  override fun saveLooksPrivate(fileId: Long, score: Float?) = read(Unit) { store.saveLooksPrivate(fileId, score) }
  override fun ensurePrivateModel(model: String) = read(false) { store.ensurePrivateModel(model) }
  override fun clearLooksPrivate() = read(0) { store.clearLooksPrivate() }
  override fun privateScoreCounts() = read(0 to 0) { store.privateScoreCounts() }
}
