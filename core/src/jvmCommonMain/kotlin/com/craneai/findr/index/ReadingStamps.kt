package com.craneai.findr.index

/**
 * What a file's reading was made by, kept in `files.info` as space-separated `key=version` tokens ("video-reading=1 soundtrack-reading=1").
 * A release that improves one part of what is extracted raises that part's version; the files whose stamp is older (or missing) are the
 * catch-up's queue for that part, and every other part of those files is left alone. A fresh read, or a changed file, writes (or clears)
 * all the stamps at once. A stamp from before this class (the bare "video-reading=1") is one token, so it still reads as it did.
 */
object ReadingStamps {
  fun has(info: String?, key: String, version: Int): Boolean = has(info, key, version.toString())

  /** A stamp whose value is a name rather than a number: a speech pack's `<pack_id>@<pack_version>` ([SoundtrackCatchUp.PACK_KEY]). */
  fun has(info: String?, key: String, value: String): Boolean = info?.split(' ')?.contains("$key=$value") == true

  /** [info] with the token for [key] set to [version]; every other token is kept, in order, and an older token of [key] is replaced. */
  fun put(info: String?, key: String, version: Int): String = put(info, key, version.toString())

  fun put(info: String?, key: String, value: String): String {
    require(value.none { it.isWhitespace() || it == '\'' }) { "a stamp is one token" }
    val kept = info?.split(' ')?.filter { it.isNotEmpty() && !it.startsWith("$key=") } ?: emptyList()
    return (kept + "$key=$value").joinToString(" ")
  }

  /** SQL: `info` has the token ([key], [version]) as a whole word. */
  fun sqlHas(key: String, version: Int) = sqlHas(key, version.toString())

  fun sqlHas(key: String, value: String): String {
    require(value.none { it.isWhitespace() || it == '\'' }) { "a stamp is one token" }
    return "instr(' ' || COALESCE(info, '') || ' ', ' $key=$value ') > 0"
  }
}
