package com.craneai.findr.diag

/**
 * Test-build memory accounting: /proc/self/smaps folded into a few named groups, with the numbers that tell cheap memory from
 * expensive memory. A file-backed page that is clean ([Group.privateCleanKb]) can be dropped and re-read any time; a dirty
 * anonymous page ([Group.privateDirtyKb]) can only go to swap/zram, and on a phone with no swap it is the real cost.
 *
 * Counts and sizes only. A group is named by kind (model weights, native heap, graphics...), never by a person's file.
 */
object SmapsGroups {
  class Group(val name: String) {
    var pssKb = 0L
    var rssKb = 0L
    var privateDirtyKb = 0L
    var privateCleanKb = 0L
    var sharedDirtyKb = 0L
    var sharedCleanKb = 0L
    var swapKb = 0L
    var sizeKb = 0L
    var maps = 0
  }

  /** A mapping's group, from the name column of its smaps header (empty for an unnamed anonymous mapping). */
  fun classify(name: String): String {
    val n = name.trim()
    return when {
      n.isEmpty() -> "anon.unnamed"
      n.startsWith("[heap]") || n.startsWith("[anon:scudo") || n.startsWith("[anon:libc_malloc") || n.startsWith("[anon:GWP-ASan") ||
        n.startsWith("[anon:jemalloc") -> "native.heap"
      n.startsWith("[stack") || n.startsWith("[anon:stack_and_tls") -> "stacks"
      n.startsWith("[anon:dalvik-main space") || n.startsWith("[anon:dalvik-large object") || n.startsWith("[anon:dalvik-free list") ||
        n.startsWith("[anon:dalvik-allocspace") || n.startsWith("[anon:dalvik-non moving") || n.startsWith("[anon:dalvik-zygote") ||
        n.startsWith("[anon:dalvik-region space") -> "java.heap"
      n.startsWith("[anon:dalvik") || n.startsWith("[anon:art") -> "art.runtime"
      n.endsWith(".art") || n.endsWith(".oat") || n.endsWith(".odex") || n.endsWith(".vdex") || n.contains("/dalvik-cache/") -> "art.images"
      n.startsWith("/dev/kgsl") || n.startsWith("/dev/mali") || n.startsWith("/dev/dri") || n.startsWith("/dev/ion") ||
        n.startsWith("/dev/dma") || n.contains("gralloc") -> "graphics"
      n.startsWith("/dev/") -> "dev.other"
      n.startsWith("/memfd:") || n.startsWith("/dev/ashmem") -> "memfd.ashmem"
      n.endsWith(".litertlm") -> "model.litertlm"
      n.endsWith(".onnx") || n.contains(".onnx") -> "model.onnx"
      n.contains("xnnpack_cache") || n.contains(".xnnpack") -> "cache.xnnpack"
      n.contains("mldrift") || n.contains("program_cache") || n.contains("weight_cache") || n.contains("serialized") -> "cache.gpu"
      n.contains("/models/") || n.contains("findr-") && (n.endsWith(".bin") || n.endsWith(".data") || n.endsWith(".tflite")) -> "model.other"
      n.contains("/cache/") || n.contains("/files/") -> "app.files"
      n.endsWith(".so") || n.contains(".so.") -> "libs.so"
      n.endsWith(".apk") || n.endsWith(".jar") || n.endsWith(".dex") || n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc") ||
        n.endsWith(".arsc") -> "apk.jar.fonts"
      n.startsWith("[anon:") -> "anon.named"
      n.startsWith("[") -> "special"
      else -> "file.other"
    }
  }

  /** Parses the text of smaps into groups (insertion order: the first group met first) plus the biggest single mappings by Pss. */
  class Result(val groups: Map<String, Group>, val top: List<Pair<String, Long>>) {
    val totalPssKb get() = groups.values.sumOf { it.pssKb }
    val totalPrivateDirtyKb get() = groups.values.sumOf { it.privateDirtyKb }
  }

  fun parse(text: String, topN: Int = 6): Result {
    val groups = LinkedHashMap<String, Group>()
    val tops = ArrayList<Pair<String, Long>>()
    var cur: Group? = null
    var curName = ""
    var curPss = 0L
    fun flush() { if (cur != null && curPss > 0) tops.add(curName to curPss) }
    for (line in text.lineSequence()) {
      if (line.isEmpty()) continue
      val head = line.substringBefore(' ')
      if (head.contains('-') && !head.contains(':') && head.all { it in '0'..'9' || it in 'a'..'f' || it == '-' }) {
        flush()
        // "start-end perms offset dev inode  name": the name is everything after the 5th field.
        val parts = line.trim().split(Regex("\\s+"), limit = 6)
        curName = if (parts.size >= 6) parts[5] else ""
        cur = groups.getOrPut(classify(curName)) { Group(classify(curName)) }
        cur.maps++
        curPss = 0
        continue
      }
      val g = cur ?: continue
      val key = line.substringBefore(':')
      val v = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: continue
      when (key) {
        "Size" -> g.sizeKb += v
        "Rss" -> g.rssKb += v
        "Pss" -> { g.pssKb += v; curPss = v }
        "Private_Dirty" -> g.privateDirtyKb += v
        "Private_Clean" -> g.privateCleanKb += v
        "Shared_Dirty" -> g.sharedDirtyKb += v
        "Shared_Clean" -> g.sharedCleanKb += v
        "Swap" -> g.swapKb += v
      }
    }
    flush()
    val top = tops.groupBy({ it.first }, { it.second }).map { (k, v) -> k to v.sum() }.sortedByDescending { it.second }.take(topN)
    return Result(groups, top)
  }

  fun readSelf(topN: Int = 6): Result = parse(java.io.File("/proc/self/smaps").readText(), topN)

  private fun mb(kb: Long) = kb / 1024

  /** One log line: every group above 4 MB Pss as `name pss/privDirty/privClean MB`, biggest first. */
  fun summary(r: Result): String =
    r.groups.values.filter { it.pssKb >= 4096 || it.privateDirtyKb >= 4096 }.sortedByDescending { it.pssKb }
      .joinToString(" ") { "${it.name}=${mb(it.pssKb)}/${mb(it.privateDirtyKb)}/${mb(it.privateCleanKb)}" } +
      " swap=${mb(r.groups.values.sumOf { it.swapKb })} total=${mb(r.totalPssKb)}"
}
