package com.craneai.findr.ui.delivery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.craneai.findr.core.DeliveryService
import com.craneai.findr.core.DeliveryState
import com.craneai.findr.core.ModelPresence
import com.craneai.findr.core.SearchEngine
import com.craneai.findr.engine.Edition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The first-run "Get the models" card and the Settings update notice read and drive the model delivery through here. */
class DeliveryViewModel(engine: SearchEngine) : ViewModel() {
  private val delivery: DeliveryService = engine.delivery

  val state: StateFlow<DeliveryState> = delivery.state

  /**
   * True once it is KNOWN that the phone has no model at all ([ModelPresence.MISSING]); false while it is installed or not known yet,
   * so a first frame drawn before anyone has looked shows the normal Home, never the card (the cold-start flash, 1.0.8). Mapped to a
   * Boolean so Home does not recompose every time the index status re-emits (every couple of seconds while the worker runs).
   */
  val noModel: StateFlow<Boolean> =
    if (!Edition.MODELS) MutableStateFlow(false)   // Trawl Go has no model to get: no first-run card, no models page
    else engine.status.map { it.models == ModelPresence.MISSING }.distinctUntilChanged()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), engine.status.value.models == ModelPresence.MISSING)

  fun downloadUrl(): String = delivery.downloadUrl()
  fun setChoice(choice: com.craneai.findr.core.ModelChoice) = delivery.setChoice(choice)
  fun watch(on: Boolean) = delivery.watch(on)
  fun installFrom(uri: String) = delivery.installFrom(uri)
  fun cancel() = delivery.cancel()
  fun acknowledge() = delivery.acknowledge()
  fun removeDownloaded() = delivery.removeDownloaded()
  fun keepDownloaded() = delivery.keepDownloaded()
  fun dismissUpdate() = delivery.dismissUpdate()
}
