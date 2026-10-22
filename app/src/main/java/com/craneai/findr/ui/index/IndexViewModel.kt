package com.craneai.findr.ui.index

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.craneai.findr.core.FindrSettings
import com.craneai.findr.core.Forgotten
import com.craneai.findr.core.IndexStatus
import com.craneai.findr.core.SearchEngine
import com.craneai.findr.index.withExcludedFolder
import com.craneai.findr.index.withGroup
import com.craneai.findr.index.withIncludedFolder
import com.craneai.findr.index.withoutExcludedFolder
import com.craneai.findr.index.withoutIncludedFolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The indexing meter, the detail sheet and Settings all read the engine's status and settings through here. */
class IndexViewModel(private val engine: SearchEngine) : ViewModel() {
  val status: StateFlow<IndexStatus> = engine.status
  val settings: StateFlow<FindrSettings> = engine.settings

  private val _deleting = MutableStateFlow(false)
  val deleting: StateFlow<Boolean> = _deleting

  /** Files per folder group for "What should Findr search?"; null until the first count comes back (or when it cannot be made). */
  private val _groupCounts = MutableStateFlow<Map<String, Int>?>(null)
  val groupCounts: StateFlow<Map<String, Int>?> = _groupCounts

  fun pause() = engine.pauseIndexing()
  fun resume() = engine.resumeIndexing()
  fun retry() = engine.startIndexing()

  fun update(transform: (FindrSettings) -> FindrSettings) {
    viewModelScope.launch { engine.updateSettings(transform) }
  }

  /** "Index on battery anyway": the way out of "Waiting for the charger". */
  fun allowOnBattery() = update { it.copy(learningSpeed = com.craneai.findr.core.LearningSpeed.FAST) }

  // ---- What should Findr search? ---------------------------------------------------------------------------------

  /** Counts the phone's files per group (a MediaStore read, a second or two). Call when the screen opens or access changes. */
  fun refreshGroupCounts() {
    viewModelScope.launch {
      val counts = runCatching { engine.folderCounts() }.getOrDefault(emptyMap())
      _groupCounts.value = counts.takeIf { it.isNotEmpty() }
    }
  }

  fun setGroup(id: String, on: Boolean) = update { it.withGroup(id, on) }
  fun addFolder(folder: String) = update { it.withIncludedFolder(folder) }
  fun removeAddedFolder(folder: String) = update { it.withoutIncludedFolder(folder) }
  fun excludeFolder(folder: String) = update { it.withExcludedFolder(folder) }
  fun stopExcluding(folder: String) = update { it.withoutExcludedFolder(folder) }

  private val _forgotten = MutableStateFlow<Forgotten?>(null)
  /** What the last "Delete everything Findr knows" removed, for the line under the button. */
  val forgotten: StateFlow<Forgotten?> = _forgotten

  fun deleteIndex() {
    if (_deleting.value) return
    viewModelScope.launch {
      _deleting.value = true
      try {
        _forgotten.value = engine.deleteIndex()
      } finally {
        _deleting.value = false
      }
    }
  }
}
