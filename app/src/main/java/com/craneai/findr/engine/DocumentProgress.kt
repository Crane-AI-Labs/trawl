package com.craneai.findr.engine

internal class DocumentProgress(val from: Int) {
  private var pieces: MutableList<Piece>? = null
  private var total = 0
  private var suffix = ""
  var completed = 0
    private set

  fun prepare(chunks: List<Pair<Long?, String>>, total: Int, suffix: String = "") {
    this.total = total
    this.suffix = suffix
    pieces = chunks.map { (page, chunk) ->
      Piece(PieceKind.CHUNK, PieceModality.TEXT, page, page, chunk.takeIf { from == 0 })
    }.toMutableList()
  }

  fun embedded(index: Int, vector: FloatArray) {
    val chunk = pieces!![index]
    pieces!![index] = Piece(chunk.kind, chunk.modality, chunk.locStart, chunk.locEnd, chunk.text, vector)
    completed++
  }

  fun result(): Processed? {
    val chunks = pieces ?: return null
    val read = from + completed
    val detail = (if (total == 0) "no text" else "chunks $read/$total") + suffix
    return Processed(if (from == 0) chunks.toList() else chunks.take(completed), read.takeIf { it < total }, detail)
  }
}
