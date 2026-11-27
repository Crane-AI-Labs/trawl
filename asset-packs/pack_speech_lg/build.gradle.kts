plugins {
  id("com.android.asset-pack")
}

assetPack {
  packName.set("pack_speech_lg")
  dynamicDelivery {
    deliveryType.set("on-demand")
  }
}
