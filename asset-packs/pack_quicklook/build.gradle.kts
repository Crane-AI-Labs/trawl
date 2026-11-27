plugins {
  id("com.android.asset-pack")
}

assetPack {
  packName.set("pack_quicklook")
  dynamicDelivery {
    deliveryType.set("on-demand")
  }
}
