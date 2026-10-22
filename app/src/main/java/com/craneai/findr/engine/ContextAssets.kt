package com.craneai.findr.engine

import android.content.Context
import com.craneai.findr.core.AssetSource
import java.io.InputStream

/** The APK's assets, as the shared code reads them. */
class ContextAssets(private val context: Context) : AssetSource {
  override fun open(name: String): InputStream = context.assets.open(name)
}
