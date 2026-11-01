package com.craneai.findr.ui

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController

/**
 * Back, once. A quick double tap on a Back arrow used to run `popBackStack()` twice: the second pop took Home off as well and left
 * a blank page (only the system Back left it). The page being left is no longer RESUMED the moment the first pop starts, and there
 * must be a page underneath, so the second tap finds nothing to do. One place for every in-app Back arrow.
 */
fun NavController.backOnce() {
  val entry = currentBackStackEntry ?: return
  if (previousBackStackEntry == null) return
  if (!entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
  popBackStack()
}

/** Navigates to [route] only while the current page is resumed and is not already [route]: the second of two quick taps does nothing. */
fun NavController.goOnce(route: String, builder: androidx.navigation.NavOptionsBuilder.() -> Unit = {}) {
  val entry = currentBackStackEntry ?: return
  if (!entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
  if (entry.destination.route == route) return
  navigate(route) { launchSingleTop = true; builder() }
}
