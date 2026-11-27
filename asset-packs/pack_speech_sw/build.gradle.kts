plugins {
  id("com.android.asset-pack")
}

assetPack {
  packName.set("pack_speech_sw")
  dynamicDelivery {
    deliveryType.set("on-demand")
  }
}
