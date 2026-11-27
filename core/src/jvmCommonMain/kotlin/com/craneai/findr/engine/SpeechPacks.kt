package com.craneai.findr.engine

/**
 * The speech packs of the opt-in speech add-on (D48), one per language, with every file pinned by size and SHA-256 as for every other
 * pack, pack.json included: a pack is installed only when each file is exactly the one Crane built, and what it says about itself
 * (frame, version, stamp) is then read from that pack.json ([SpeechPack]), never from here. Built on Leonardo (packs in
 * /mnt/data/findr-lugasr/packs/<pack>-student/ on Crane); a new version is a new entry here, and installing it re-reads the voice notes
 * already in the index (index.SoundtrackCatchUp.PACK_KEY).
 *
 * Laid out the same everywhere: at the root of the `trawl-speech-<language>` folder or zip in Downloads (or in that folder inside the
 * models bundle), and in `files/models/speech-<language>/` on the phone. The licence files travel with the model.
 */
object SpeechPacks {
  /** One language's pack: the catalog's [id] (PackIds.SPEECH_PACKS), where it installs ([dir]), its Downloads names, its files. */
  class Entry(val id: String, val language: String, val dir: String, val folderName: String, val files: List<AsrModelFile>) {
    /** The required files' bytes: what the catalog says the download weighs (a test keeps them equal). */
    val sizeBytes: Long get() = files.sumOf { it.size }
  }

  /** Luganda: `lg-student` @ 2026112201, CC-BY-SA-4.0. */
  val lg = Entry(
    id = com.craneai.findr.core.PackIds.SPEECH_LG, language = "lg", dir = "speech-lg", folderName = "trawl-speech-lg",
    files = listOf(
      AsrModelFile("pack.json", 656, "3fb3639f460dda7f6b39f7428e4fdc80f1a2104c916a2be3c63c7717395cc296"),
      AsrModelFile("model.int8.onnx", 131_387_952, "cb7299ae30fa9c0ca36b3f607ed85cc3e543512fa8e132e72f635bf4ff5ca464"),
      AsrModelFile("tokens.txt", 4_537, "a5cd0c3625be3cfc0ac53e8ae21c28e2b0492a44766b4db0787ff017ce4a693a"),
      AsrModelFile("NOTICE.md", 1_864, "ca1cc6471b1d269b8a0cf948e459918f5545d9ad1a88bfeae155f4a2d3e3c3bd"),
      AsrModelFile("MODEL_CARD.md", 2_839, "bf7db3127b41f976b26920483590b4844f9d7cad6b6d5b3ce117faf918d588db"),
      AsrModelFile("licences/WaxalNLP_README.md", 36_256, "60b3ffe00b11d23790e3176d42500f7ea83d8e6e663adef9f560ff438f5c475e"),
      AsrModelFile("licences/fsicoli_cv17_README.md", 4_472, "5d7f13a790c3f4de73ec28608570d7a4619bce675f37bdd42af47dfa6bfb0281"),
      AsrModelFile("licences/nvidia_parakeet-tdt_ctc-110m_README.md", 10_120, "b3b64ddb87cc162ebba6f1cbdc5770d036615c845703622a12a2eb1c6792114a"),
    ),
  )

  /** Runyankole: `nyn-student` @ 2026112201, CC-BY-SA-4.0. */
  val nyn = Entry(
    id = com.craneai.findr.core.PackIds.SPEECH_NYN, language = "nyn", dir = "speech-nyn", folderName = "trawl-speech-nyn",
    files = listOf(
      AsrModelFile("pack.json", 676, "33a285334f3223b597e69bd95278d531027df532395dc51a965da549b52b41c8"),
      AsrModelFile("model.int8.onnx", 131_255_844, "90e7b8183b1972d96b1f03c6969ef7c652c38709a993b4c68a4435a7d7966846"),
      AsrModelFile("tokens.txt", 2_065, "864638bd98ef0b912aaadde1dfba512e2cac166506452ebbe9a5af17de328fac"),
      AsrModelFile("NOTICE.md", 1_614, "444bbd2901bcb1ae6f1b1386616177e4cdceaa5aa0a24a7eaf3662ad3f1db8c6"),
      AsrModelFile("MODEL_CARD.md", 2_106, "ef927de91f949ad234879770732175d9b3d7dd971b108da42905a21cd87f2fcf"),
      AsrModelFile("licences/WaxalNLP_README.md", 36_256, "60b3ffe00b11d23790e3176d42500f7ea83d8e6e663adef9f560ff438f5c475e"),
      AsrModelFile("licences/nvidia_parakeet-tdt_ctc-110m_README.md", 10_120, "b3b64ddb87cc162ebba6f1cbdc5770d036615c845703622a12a2eb1c6792114a"),
    ),
  )

  /** Acholi: `ach-student` @ 2026112201, CC-BY-SA-4.0. */
  val ach = Entry(
    id = com.craneai.findr.core.PackIds.SPEECH_ACH, language = "ach", dir = "speech-ach", folderName = "trawl-speech-ach",
    files = listOf(
      AsrModelFile("pack.json", 670, "3ae3cc71f2719c312c81eac5d3674094fa4bdbe163626b53620ae98f137a2094"),
      AsrModelFile("model.int8.onnx", 131_255_845, "0281ac8587a5a8e7287ca40ad6d1623541f8b9522060187d5fd4e2e92568bd3c"),
      AsrModelFile("tokens.txt", 2_170, "54db1d03e089f8ac4f5c64661a8959800cc9523b81fc0ada8cbdeb188cba19d8"),
      AsrModelFile("NOTICE.md", 1_580, "adc5f32970bea868838a6d269f2455187fdda578da9a8653e1dfbe7b518625e0"),
      AsrModelFile("MODEL_CARD.md", 2_178, "5de03e53f1c76a6c17290b32c108e95fc723f37b44893e665ea3990cc4873c18"),
      AsrModelFile("licences/WaxalNLP_README.md", 36_256, "60b3ffe00b11d23790e3176d42500f7ea83d8e6e663adef9f560ff438f5c475e"),
      AsrModelFile("licences/nvidia_parakeet-tdt_ctc-110m_README.md", 10_120, "b3b64ddb87cc162ebba6f1cbdc5770d036615c845703622a12a2eb1c6792114a"),
    ),
  )

  /** Swahili: `sw-student` @ 2026112201, CC-BY-4.0. */
  val sw = Entry(
    id = com.craneai.findr.core.PackIds.SPEECH_SW, language = "sw", dir = "speech-sw", folderName = "trawl-speech-sw",
    files = listOf(
      AsrModelFile("pack.json", 661, "95e261f2ad3d56560d9fefcd5c704f77e4118657fee7d51dbf3ce2cb5011c01b"),
      AsrModelFile("model.int8.onnx", 131_387_883, "120cce74bfb41ace7ef901b260b97a3b25feafccb0d0f78231bb8c960a45a5df"),
      AsrModelFile("tokens.txt", 4_760, "9b4e26d9339334918c0e8f641f3b985a4fa441e49147ffca0f9f6e38094f3224"),
      AsrModelFile("NOTICE.md", 1_448, "5c7ac6282a0fa6e4cd290c33579d2986b6adac4f118371dbbf3bb2a3e634f937"),
      AsrModelFile("MODEL_CARD.md", 2_207, "49d80d909a3a9876d40c7fd7a31efe8bc1d308550b6da2a5b11c257fc3efea11"),
      AsrModelFile("licences/fsicoli_cv17_README.md", 4_472, "5d7f13a790c3f4de73ec28608570d7a4619bce675f37bdd42af47dfa6bfb0281"),
      AsrModelFile("licences/nvidia_parakeet-tdt_ctc-110m_README.md", 10_120, "b3b64ddb87cc162ebba6f1cbdc5770d036615c845703622a12a2eb1c6792114a"),
    ),
  )

  /** Lusoga: `xog-student` @ 2026112201, CC-BY-SA-4.0. */
  val xog = Entry(
    id = com.craneai.findr.core.PackIds.SPEECH_XOG, language = "xog", dir = "speech-xog", folderName = "trawl-speech-xog",
    files = listOf(
      AsrModelFile("pack.json", 672, "686a65cfb17eed1bf02a7e72299bf872b04b3e95a6dbf7cabb2224ed4840581a"),
      AsrModelFile("model.int8.onnx", 131_255_844, "9b37d6926e8d6c85b1ea847e5fc669679a11bc8dcef98bfec3f8c5af2d210835"),
      AsrModelFile("tokens.txt", 2_138, "a2ec711961200e684acc26ae4c03a58ee446eaf514969ad5d3796e211235fa38"),
      AsrModelFile("NOTICE.md", 1_585, "efeac82a1948695fcf0ebfca7194978d5bd31b45f30ceb52edd5ab15a2b17bb6"),
      AsrModelFile("MODEL_CARD.md", 2_059, "11dca1ae28febb272dd2d2b4d943b6f846c5267bc6670434b54786c36f4ff14c"),
      AsrModelFile("licences/WaxalNLP_README.md", 36_256, "60b3ffe00b11d23790e3176d42500f7ea83d8e6e663adef9f560ff438f5c475e"),
      AsrModelFile("licences/nvidia_parakeet-tdt_ctc-110m_README.md", 10_120, "b3b64ddb87cc162ebba6f1cbdc5770d036615c845703622a12a2eb1c6792114a"),
    ),
  )

  val all: List<Entry> = listOf(lg, nyn, ach, sw, xog)

  fun byId(id: String?): Entry? = all.firstOrNull { it.id == id }
}
