plugins {
  id("com.android.asset-pack")
}

assetPack {
  packName.set("pack_speech_ach")
  dynamicDelivery {
    deliveryType.set("on-demand")
  }
}
