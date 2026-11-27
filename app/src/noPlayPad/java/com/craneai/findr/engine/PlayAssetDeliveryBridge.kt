package com.craneai.findr.engine

import android.content.Context

/**
 * Sideload & Trawl Go stub for Play Asset Delivery: keeps zero Play Core library dependency in non-Play builds and
 * hands straight through to [DownloadsFolderSource].
 */
object PlayAssetDeliveryBridge {
  @Suppress("UNUSED_PARAMETER")
  fun createPackSource(context: Context, fallback: ModelPackSource): ModelPackSource = fallback
}
