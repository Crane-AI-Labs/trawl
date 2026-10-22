package com.craneai.findr.engine

import android.content.Context

/** The one place that builds an [Embedder] (Trawl Go: [NoModelEmbedder], which never loads or needs a model). */
fun newEmbedder(@Suppress("UNUSED_PARAMETER") context: Context, profile: MemoryProfile): Embedder = NoModelEmbedder(profile)
