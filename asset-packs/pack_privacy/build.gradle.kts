plugins {
  id("com.android.asset-pack")
}

assetPack {
  packName.set("pack_privacy")
  dynamicDelivery {
    deliveryType.set("on-demand")
  }
}
