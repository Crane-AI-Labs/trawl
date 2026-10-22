package com.craneai.findr.engine

import com.craneai.findr.core.FindrLog as Log
import com.craneai.findr.core.FindrLog.redact
import com.craneai.findr.core.PackCatalog
import com.craneai.findr.core.PackCopy
import com.craneai.findr.core.PackDef
import com.craneai.findr.core.PackManager
import com.craneai.findr.core.PackProblem
import com.craneai.findr.core.PackState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

private const val TAG = "FindrPacks"

/** What the pack manager needs from the engine around it. Lambdas, so this class has no Android types and is unit-tested. */
class PackHost(
  /** Switch the pack's setting on or off. */
  val setEnabled: suspend (PackDef, Boolean) -> Unit,
  /** Called before a pack's files are deleted: stop whatever has them open (the speech models). */
  val releaseForRemoval: suspend (PackDef) -> Unit,
  /** Called after any change: refresh the meter, and let the indexer pick the new pack up. */
  val changed: () -> Unit,
)

/**
 * Adds and removes packs and keeps their state. State is always reconciled with the files on disk ([refresh]), so a
 * pack that an auto-install, `adb push` or an older build put in place shows as installed, and a failed try stays
 * visible until the next one.
 */
class EnginePackManager(
  private val modelsDir: File,
  private val source: ModelPackSource,
  private val choices: PackChoices,
  private val host: PackHost,
  private val scope: CoroutineScope,
  private val io: CoroutineDispatcher = Dispatchers.IO,
  private val log: (String) -> Unit = { Log.i(TAG, it) },
  /** Where each pack's files live; tests pass a small synthetic manifest instead of the 140 MB one. */
  private val registry: (String) -> PackFiles? = PackFilesRegistry::of,
) : PackManager {
  private val _states = MutableStateFlow(PackCatalog.all.associate { it.id to disk(it) })
  override val states: StateFlow<Map<String, PackState>> = _states

  override fun howToAdd(id: String): PackCopy? = PackCatalog.byId(id)?.let(source::howToAdd)

  /** Re-reads the disk. Cheap (a few `stat`s); the engine calls it with every meter refresh. */
  fun refresh() {
    _states.update { cur ->
      PackCatalog.all.associate { def ->
        val now = cur[def.id]
        def.id to when {
          now is PackState.Installing -> now
          else -> disk(def).let { d -> if (d is PackState.NotInstalled && now is PackState.Failed) now else d }
        }
      }
    }
  }

  override fun install(id: String) {
    val def = PackCatalog.byId(id) ?: return
    if (_states.value[id] is PackState.Installing) return
    val files = registry(id)
    if (!def.available || files == null) {
      set(id, PackState.Failed(PackProblem.UNAVAILABLE))
      return
    }
    choices.setDeclined(id, false)
    set(id, PackState.Installing(0, def.sizeBytes))
    scope.launch(io) {
      PackLocks.of(id).withLock {
        val result = try {
          run(def, files)
        } catch (e: CancellationException) {
          throw e
        } catch (t: Throwable) {
          log("${def.id}: install failed: ${Log.brief(t)}")
          com.craneai.findr.diag.Diag.handled(com.craneai.findr.diag.HandledKind.PACK_INSTALL, t)
          PackState.Failed(PackProblem.BAD_FILES, t.message)
        }
        set(id, result)
        if (result is PackState.Installed) {
          host.setEnabled(def, true)
          log("${def.id}: installed, ${result.bytes / 1_000_000} MB")
        }
        host.changed()
      }
    }
  }

  private suspend fun run(def: PackDef, files: PackFiles): PackState {
    val installer = AsrModelInstaller(File(modelsDir, files.dir), files.manifest)
    if (installer.complete()) return PackState.Installed(installer.installedBytes())
    val found = source.open(files)
    when (found) {
      is PackSourceResult.NeedsFileAccess -> return PackState.Failed(PackProblem.NEEDS_FILE_ACCESS)
      is PackSourceResult.NotFound -> return PackState.Failed(PackProblem.NOT_FOUND)
      is PackSourceResult.Unavailable -> return PackState.Failed(PackProblem.UNAVAILABLE, found.reason)
      is PackSourceResult.Unreadable -> {
        log("${def.id}: not installed: ${redact(found.detail)}")
        return PackState.Failed(PackProblem.BAD_FILES, found.detail)
      }
      is PackSourceResult.Found -> Unit
    }
    val problems = mutableListOf<String>()
    try {
      for (candidate in found.sources) {
        try {
          when (val r = installer.install(candidate) { done, total -> set(def.id, PackState.Installing(done, total)) }) {
            is AsrModelInstaller.Outcome.Ready -> return PackState.Installed(installer.installedBytes())
            is AsrModelInstaller.Outcome.Incomplete -> problems += r.problems
          }
        } catch (t: Throwable) {
          problems += "${candidate.label}: $t"
        }
      }
    } finally {
      found.sources.forEach { runCatching { it.close() } }
    }
    log("${def.id}: not installed: ${problems.size} problems (${redact(problems.joinToString("; "))})")
    return PackState.Failed(PackProblem.BAD_FILES, problems.firstOrNull())
  }

  override fun remove(id: String) {
    val def = PackCatalog.byId(id) ?: return
    val files = registry(id) ?: return
    if (_states.value[id] is PackState.Installing) return
    choices.setDeclined(id, true)   // before anything else: an auto-install must not undo this
    scope.launch(io) {
      PackLocks.of(id).withLock {
        host.releaseForRemoval(def)
        val freed = File(modelsDir, files.dir).let { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }.also { dir.deleteRecursively() } }
        host.setEnabled(def, false)
        set(id, disk(def))
        log("${def.id}: removed, freed ${freed / 1_000_000} MB")
        host.changed()
      }
    }
  }

  override fun decline(id: String) {
    choices.setDeclined(id, true)
  }

  /**
   * A background install (engine start-up, the index worker) found the pack's file in Downloads but could not use it: show
   * that on the card, as the Add button would, instead of leaving the pack quietly "not installed". Never over an install in
   * progress or a pack that is in place.
   */
  fun reportAutoInstall(id: String, failed: PackState.Failed) {
    _states.update { cur -> if (cur[id] is PackState.Installing || cur[id] is PackState.Installed) cur else cur + (id to failed) }
    log("$id: automatic install failed: ${failed.problem}${failed.detail?.let { " (${redact(it)})" }.orEmpty()}")
  }

  private fun set(id: String, state: PackState) = _states.update { it + (id to state) }

  private fun disk(def: PackDef): PackState {
    val files = registry(def.id) ?: return PackState.NotInstalled
    val installer = AsrModelInstaller(File(modelsDir, files.dir), files.manifest)
    return if (installer.complete()) PackState.Installed(installer.installedBytes()) else PackState.NotInstalled
  }
}
