package com.craneai.findr.engine

/** The runtime's own failure type (ONNX Runtime): the privacy pass stops instead of failing every file when the model itself is broken. */
internal fun isModelRuntimeError(t: Throwable): Boolean = t is ai.onnxruntime.OrtException
