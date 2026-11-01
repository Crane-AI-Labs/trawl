package com.craneai.findr.ui.atlas

import com.craneai.findr.ui.addons.AddOnsViewModel
import com.craneai.findr.ui.delivery.DeliveryViewModel
import com.craneai.findr.ui.index.IndexViewModel
import com.craneai.findr.ui.search.SearchViewModel
import java.time.DayOfWeek
import kotlinx.coroutines.Dispatchers

/** The app's real view models over an [AtlasEngine]; the feed is built inline and the clock is fixed. */
class Vms(val engine: AtlasEngine = AtlasEngine()) {
  val search = SearchViewModel(engine, clock = { ATLAS_NOW }, weekStart = { DayOfWeek.MONDAY }, feedDispatcher = Dispatchers.Unconfined)
  val index = IndexViewModel(engine)
  val addOns = AddOnsViewModel(engine)
  val delivery = DeliveryViewModel(engine)
}
