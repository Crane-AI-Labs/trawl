package com.craneai.findr.ui.addons

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.craneai.findr.core.PackCatalog
import com.craneai.findr.core.PackCopy
import com.craneai.findr.core.PackRow
import com.craneai.findr.core.SearchEngine
import com.craneai.findr.core.packRows
import com.craneai.findr.engine.Edition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings → Add-ons and the onboarding step read and drive the model packs through here. */
class AddOnsViewModel(private val engine: SearchEngine) : ViewModel() {
  // Trawl Go has no add-ons (every pack is a model): no rows, so no Settings section, no onboarding offer.
  val rows: StateFlow<List<PackRow>> =
    if (!Edition.MODELS) MutableStateFlow(emptyList())
    else combine(engine.packs.states, engine.settings) { states, settings -> packRows(PackCatalog.all, states, settings) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), packRows(PackCatalog.all, engine.packs.states.value, engine.settings.value))

  fun install(id: String) = engine.packs.install(id)
  fun remove(id: String) = engine.packs.remove(id)
  fun notNow(id: String) = engine.packs.decline(id)
  fun howToAdd(id: String): PackCopy? = engine.packs.howToAdd(id)

  fun setOn(id: String, on: Boolean) {
    val def = PackCatalog.byId(id) ?: return
    viewModelScope.launch { engine.updateSettings { def.withEnabled(it, on) } }
  }
}
