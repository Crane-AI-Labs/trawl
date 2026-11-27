# Distilled Sees words

The Full Android edition bundles `app/src/full/assets/sees/`: fp16 row-major weights, fp32 little-endian biases and one UTF-8 tag per line. The head applies to the shipped EmbeddingGemma image-frame vectors, not quick-look/CLIP vectors. Go does not bundle or run it.

`SeesHead` rounds its input to the index's fp16 representation. It takes the most probable tags per frame, drops below-threshold and negated tags, then keeps distinct tags across the video in probability order. Threshold and limits live in `SeesHead`. The training and teacher/data provenance are in `THIRD_PARTY_NOTICES.md`.

A completed fresh video read runs the head over its stored frame vectors, including all windows of a long video. Each frame is scored once for that reading; no decode, embedding or text vector is added. The resulting `sees` segment has no locator, vector or search snippet.

`sees_fts` is deliberately separate from `fts`. Sees words cannot alter the ordinary index's BM25 statistics, prefix fallback, date carriers or shared word-file cap. Only a query whose `Fusion.kindHints` contains `VIDEO` can add Sees coverage. Sees matches have their own bounded candidate pool, do not count twice with ordinary words, and do not qualify as literal phrases or file-name matches.

Older understood videos lacking the current `sees-reading` stamp are caught up one at a time on the charger, before decode-based catch-ups. The queue is the missing stamp, so stopping before the atomic words-and-stamp transaction retries that video. Existing words, vectors, state and dates remain untouched. A changed vector generation or file reading rejects an in-flight result; stale-model frames wait for a fresh read. A model change removes the old head's words.

`scripts/sees/replay.init.gradle` adds the optional replay/CPU test to the desktop test build. Set `SEES_WORLDS` to a file of `name=world-path` entries and `SEES_OUT` to a new output directory. The replay runs baseline and real-head paths through `AppPathHarness`, the production `IndexStore`, `Searcher` and `Fusion`. CPU measurements and per-video tag lines are raw files under that output directory. Use the shared Gradle lock and the parked-task build/memory rules.

The world loader preserves each segment's declared `modality` and understands audio segments, matching the frozen video-levers loader. A Sounds vector must not silently enter the transcript/document scale. Bench-v2 metrics exclude the original bench queries carried in the same world; those carried queries are reported separately, not confused with the original v1-world no-loss gate.
