package com.craneai.findr.engine

/**
 * The quick-look pack (decisions D2, D10): Microsoft's TinyCLIP ViT-61M/32 Text-29M (`wkcn/TinyCLIP-ViT-61M-32-Text-29M-LAION400M`,
 * weights MIT), both towers exported to ONNX by the experiment code. The image tower embeds a photo in ~0.15 s
 * on the Galaxy A06's CPU (2 threads, full clocks), against ~4-5 s for EmbeddingGemma there, so a whole gallery becomes
 * searchable by meaning within minutes while EmbeddingGemma fills in at full quality. The text tower embeds the (English) query
 * with OpenAI's CLIP vocabulary ([ClipTokenizer], the same as D2's).
 *
 * D10 replaced laion CLIP-B/32 (D2), whose model card puts any deployed use out of scope. TinyCLIP over SigLIP 2 B/32 (Apache-2.0,
 * better quick-only r@1, 0.81 vs 0.76): 117 MB instead of 384 MB to download and a 540 MB smaller text-tower peak, for a gap
 * that lasts only until EmbeddingGemma catches up (the research notes, D10).
 *
 * Precision, each tower in the format that measured best on the phones (the research notes, D10):
 *  - image: dynamically quantised int8, per output channel (onnxruntime quantize_dynamic). On the A06, against fp32 on the same
 *    20 real inputs: 0.9909 mean / 0.9771 min (the Mac's same graph: 0.9933 / 0.9839), at 152 ms per photo against 222 ms
 *    weight-only and 211 ms for laion's int8. The weight-only form was 0.9991 / 0.9981 but slower than laion, because its
 *    maths stays float and this ViT has 5.3 G MACs to laion B/32's ~4.4 G.
 *  - text: weight-only int8 (the experiment code), 0.9994 / 0.9977, since dynamic int8 text graphs have
 *    given WRONG vectors on these ARM CPUs (laion's text tower: 0.43-0.90). Never ship a dynamic text tower.
 *
 * Installed like the speech pack (PackFilesRegistry): `findr-quicklook-models/` or `findr-quicklook-models*.zip` in Downloads,
 * built by scripts/pack-quicklook-models.sh, checked file by file into `files/models/quicklook/`.
 */
object QuickLookPack {
  const val FOLDER_NAME = "findr-quicklook-models"
  const val DIR = "quicklook"
  const val IMAGE = "tinyclip-61m-32/image.dyn8pc.onnx"
  const val TEXT = "tinyclip-61m-32/text.wo8.onnx"

  val files: List<AsrModelFile> = listOf(
    AsrModelFile(IMAGE, 62_346_674, "88c62124467afe76420f238c9751a0338505fee1c5837a62e7c44a178924e4ab"),
    AsrModelFile(TEXT, 54_817_350, "8ad3a70e961f60c357bf2f1ac5749c215997234024986623cab5b7e1052a1075"),
  )

  /** Files earlier packs put in the pack folder that nothing reads any more: removed (with their emptied folder) after an install. */
  val RETIRED = listOf(
    "clip-vit-b32-laion2b/image.int8.onnx", "clip-vit-b32-laion2b/text.wo_pc.onnx",   // D2: laion CLIP-B/32
    "clip-vit-b32-laion2b/text.int8.onnx", "clip-vit-b32-laion2b/text.fp16w.onnx",
    "siglip2-b32-256/image.wo8.onnx", "siglip2-b32-256/text.wo8.onnx", "siglip2-b32-256/tokenizer.vocab",   // D10's test phones only
    "tinyclip-61m-32/image.wo8.onnx",   // D10's first TinyCLIP image tower (weight-only, slower)
  )

  /** The image tower's input: the whole picture squashed to SIZE×SIZE (Pillow bicubic), then CLIP's mean and deviation. */
  const val SIZE = 224
  val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
  val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

  /** Vector length of both towers. */
  const val DIM = 512

  /** Changes when the vectors would: a different export or preprocessing makes stored quick-look vectors incomparable. */
  const val FINGERPRINT = "tinyclip-61m-32-dyn8pc:88c62124:squash224-bicubic"
}
