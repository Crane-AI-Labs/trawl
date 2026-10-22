package com.craneai.findr

import java.io.File

/** A Findr source file by its path under com/craneai/findr/, wherever it lives now: this module or :core's shared sources. */
fun findrSource(path: String): File =
  listOf("src/main/java", "../core/src/jvmCommonMain/kotlin")
    .map { File("$it/com/craneai/findr/$path") }
    .firstOrNull { it.isFile } ?: error("no source file $path")
