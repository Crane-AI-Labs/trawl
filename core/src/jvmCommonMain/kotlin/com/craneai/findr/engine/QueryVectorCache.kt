package com.craneai.findr.engine

/**
 * An in-memory cache of query vectors: the same text through the same model gives the same vector, so a repeated query
 * (retyping, backspacing over a letter, the quick panel after the main screen) skips the ~0.3-0.5 s embed and the answer is
 * exactly what an embed would have given.
 *
 * Queries are sensitive, so this is memory only: nothing is written to disk, nothing is logged (no text, only counts), and
 * the engine clears it when the index is deleted, when the app lock closes and when the system trims memory
 * ([clear]). A key is the model [fingerprint] plus the [normalise]d text; a different fingerprint empties the cache first,
 * so a vector from an old model can never answer for a new one.
 *
 * Vectors are kept as fp32, not fp16: fp16 would round them and the cache would no longer be exact (256 x 768 x 4 bytes is
 * ~0.8 MB). Callers get a copy, so no one can change a cached vector.
 *
 * Thread-safe. An embed that was running when [clear] was called is not stored afterwards (a generation counter), so a clear
 * cannot be undone by a slow query that finishes after it.
 */
class QueryVectorCache(private val capacity: Int = DEFAULT_CAPACITY) {
  private val lock = Any()
  private var generation = 0L
  private var fingerprint: String? = null
  private val entries = object : LinkedHashMap<String, FloatArray>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > capacity
  }

  var hits = 0L; private set
  var misses = 0L; private set
  val size: Int get() = synchronized(lock) { entries.size }

  /**
   * The vector for [text] under [fingerprint]: the cached one, or `embed(normalise(text))` (stored when it succeeds). A failed,
   * cancelled or empty (null) embed stores nothing. [embed] is not called under the lock.
   */
  suspend fun get(fingerprint: String, text: String, embed: suspend (String) -> FloatArray?): FloatArray? {
    val key = normalise(text)
    val startedAt = synchronized(lock) {
      if (this.fingerprint != fingerprint) { entries.clear(); this.fingerprint = fingerprint }
      entries[key]?.let { hits++; return it.copyOf() }
      misses++
      generation
    }
    val vector = embed(key) ?: return null   // no vector (the model could not load): nothing to keep
    synchronized(lock) {
      if (generation == startedAt && this.fingerprint == fingerprint) entries[key] = vector.copyOf()
    }
    return vector
  }

  /** Forget everything (delete, app lock, memory trim). */
  fun clear() = synchronized(lock) {
    generation++
    entries.clear()
    fingerprint = null
  }

  companion object {
    const val DEFAULT_CAPACITY = 256

    /**
     * Trim and collapse runs of whitespace to one space. This is also the text the embed is given, so the key and the input
     * always agree (two spellings of the same query share one vector). Case is kept: the model reads it.
     */
    fun normalise(text: String): String = text.trim().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")
  }
}
