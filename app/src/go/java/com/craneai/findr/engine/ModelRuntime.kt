package com.craneai.findr.engine

/** Trawl Go has no model runtime, so no failure is one. */
internal fun isModelRuntimeError(t: Throwable): Boolean = false
