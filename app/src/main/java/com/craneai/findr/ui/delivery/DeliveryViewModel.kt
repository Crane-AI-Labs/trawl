package com.craneai.findr.ui.delivery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.craneai.findr.core.DeliveryService
import com.craneai.findr.core.DeliveryState
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
   * True while the phone has no model at all. Mapped to a Boolean so Home does not recompose every time the index status
   * re-emits (every couple of seconds while the worker runs).
   */
  val noModel: StateFlow<Boolean> =
    if (!Edition.MODELS) MutableStateFlow(false)   // Trawl Go has no model to get: no first-run card, no models page
    else engine.status.map { it.modelBytes == 0L }.distinctUntilChanged()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), engine.status.value.modelBytes == 0L)

  fun downloadUrl(): String = delivery.downloadUrl()
  fun watch(on: Boolean) = delivery.watch(on)
  fun installFrom(uri: String) = delivery.installFrom(uri)
  fun cancel() = delivery.cancel()
  fun acknowledge() = delivery.acknowledge()
  fun removeDownloaded() = delivery.removeDownloaded()
  fun keepDownloaded() = delivery.keepDownloaded()
  fun dismissUpdate() = delivery.dismissUpdate()
}
