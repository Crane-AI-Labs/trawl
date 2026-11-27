plugins {
  id("com.android.asset-pack")
}

assetPack {
  packName.set("pack_speech")
  dynamicDelivery {
    deliveryType.set("on-demand")
  }
}
