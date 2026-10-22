package com.craneai.findr.engine

/**
 * The "Blur private-looking previews" add-on pack: Marqo/nsfw-image-detection-384 (Apache-2.0), a ViT-tiny/16 fine-tune at
 * 384 px, exported to ONNX (fp32) by scripts/export_private_model.py. It is an add-on, not part of the APK, so the 22 MB cost
 * falls only on people who want the feature (the speech and quick-look packs work the same way).
 *
 * Installed like the other packs (PackFilesRegistry): `findr-private-models/` or `findr-private-models*.zip` in Downloads,
 * built by scripts/pack-private-models.sh, checked file by file (size and SHA-256) into `files/models/private/`. The licence
 * text travels with the model, as Apache-2.0 asks.
 */
object PrivatePack {
  const val FOLDER_NAME = "findr-private-models"
  const val DIR = "private"
  const val MODEL = "nsfw-384/nsfw-384.onnx"
  const val LICENCE = "nsfw-384/LICENSE.txt"
  const val SOURCE = "nsfw-384/SOURCE.txt"

  val files: List<AsrModelFile> = listOf(
    AsrModelFile(MODEL, 22_489_942, "74b709a3bfe840a31cc1dcb9f0fac56aba78ec0f23cacd7283667c7d22452cac"),
    AsrModelFile(LICENCE, 11_358, "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"),
    AsrModelFile(SOURCE, 353, "b918d419e78650cd8b2cac7d79905ac6b70e911adcfe5cf275dcf9f628bab936"),
  )
}
