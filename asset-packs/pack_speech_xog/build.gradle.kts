plugins {
  id("com.android.asset-pack")
}

assetPack {
  packName.set("pack_speech_xog")
  dynamicDelivery {
    deliveryType.set("on-demand")
  }
}
