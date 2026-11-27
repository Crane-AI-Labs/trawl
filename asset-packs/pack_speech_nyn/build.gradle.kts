plugins {
  id("com.android.asset-pack")
}

assetPack {
  packName.set("pack_speech_nyn")
  dynamicDelivery {
    deliveryType.set("on-demand")
  }
}
