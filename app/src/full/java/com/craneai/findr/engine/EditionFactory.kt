package com.craneai.findr.engine

import android.content.Context

/** The one place that builds an [Embedder] (the full edition: EmbeddingGemma on LiteRT-LM). */
fun newEmbedder(context: Context, profile: MemoryProfile): Embedder = LiteRtEmbedder(context, profile)
