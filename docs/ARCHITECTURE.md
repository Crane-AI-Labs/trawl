# How Trawl works

This is the design in plain words. The code is the final word: each section names the files to read.
All paths are under `app/src/main/java/com/craneai/findr/`.

Trawl is one Android app that finds the files already on a phone by what they are about. Everything happens on the phone.
The app has no internet permission, so nothing it reads can leave.

## The big picture

```
 files on the phone
        |
   crawl  (index/Crawler.kt)        lists files, notes what is new or changed, skips clutter
        |
   words pass (engine/)             names, document text, text in photos (OCR), captions of speech
        |                           -> searchable by exact words within minutes
   quick look (engine/QuickLook*)   a small, fast image model gives every photo a first meaning vector
        |                           -> searchable by meaning within minutes
   full quality (engine/Embedder)   EmbeddingGemma reads each file properly, replacing the quick vector
        |
   SQLite database + vector file (index/, search/)
        |
   search (search/Searcher.kt, Fusion.kt)
```

Each stage makes a file searchable a little better than the one before, and none of them has to finish before the next
search works. A file that has only been through the words pass is already found by its name and its words.

## Indexing

**The crawl** (`index/Crawler.kt`, `index/Rules.kt`). Trawl walks the folders it may read and records every file in a SQLite
table with a kind (photo, screenshot, document, PDF, video, audio, other), a size and a modified time. Later runs only look at
what changed. Hidden folders, trash, thumbnail caches, app databases and chat backups are clutter and are skipped, as are damaged
or fake files, which are noted rather than crashing the app. A file in a format Trawl cannot read yet is still found by its name.
Android also tells the app when files change (`engine/ChangeWorker.kt`), so new photos are picked up quickly.

**The queue** (`index/Schema.kt`, `engine/IndexWorker.kt`). Pending files are worked in an order that favours what the person
probably wants next: anything modified in the last 14 days goes first, newest first; older files follow by kind (screenshots,
photos, documents, video, audio). A voice note recorded today therefore does not wait behind a ten-year photo backlog.

**The words pass.** This is the fast stage and it needs no model. It reads the file's name, the text of documents (`extract/`:
plain text, PDF, Word, spreadsheets, slides, e-books and more), the text inside photos and screenshots (on-device OCR), the words
on screen in video, and, with the optional speech pack, what is said in audio and video. The words go into a SQLite full-text
index (FTS5). Text read out of a picture goes into the word index only, not the meaning index: embedding it made screenshots of a
message compete with the document it copied.

**Quick look** (`engine/QuickLookPass.kt`, `engine/QuickLookPack.kt`). A small image model (TinyCLIP) runs on two CPU threads and
gives every photo, screenshot and the first frame of every video a meaning vector within minutes of install. English queries are
embedded by the same model's text tower, so a gallery is searchable by meaning long before the big model has finished. Quick
vectors are stored next to the full ones and are dropped when the full vector for that file is saved, so the end state is the big
model's quality.

**Full quality** (`engine/Embedder.kt`, `engine/FileProcessor.kt`). EmbeddingGemma 2 turns text chunks (about 400 characters),
images and, on the larger model file, audio into 768-number vectors. Text runs on the CPU and images on the GPU where one is
usable (`engine/GpuGate.kt`). Videos are sampled at scene changes (`extract/ShotDetector.kt`) and each sampled frame becomes a
vector. This is the slow stage: hours for a big gallery on a budget phone, which is why it is paced as described below.

**Upgrading a model.** When the model changes, old photo vectors are marked stale instead of deleted. They keep working while
the files are queued to be read again, so there is never a gap in what can be found.

## Search

Two things are matched, and then combined.

**Meaning.** The query is embedded into a vector. Its cosine similarity to every stored vector is computed in
`search/VectorIndex.kt`. The vectors live in a memory-mapped file (`search/VectorFile.kt`), not on the Java heap, so a large
library does not cost RAM. The file is only a cache: SQLite is the truth, and a missing or damaged vector file is rebuilt from it.
For very large libraries a two-stage scan first scores every row on a short prefix of each vector, then rescores the best few
thousand at full length. Below a size threshold the exact scan runs, and the answers are identical.

**Words.** The same query is looked up in the full-text index. A file's word coverage is the share of the query's words found
in it.

**Fusion** (`search/Fusion.kt`). Trawl uses the hybrid ranking from DigUp, not reciprocal-rank fusion. Plain rank fusion breaks
on mixed media: photos have no words, so any document that matches a common word outranks them. Instead:

- Meaning scores are turned into z-scores, per kind of media, on a per-query scale, so a photo and a document can be compared.
- A file competes only if it stands out in meaning (z of 2 or more) or contains at least half of the query's words. This is what
  makes "nothing found" an honest answer.
- Its score is the meaning part plus a word part that grows with coverage, plus a bonus if the query names the file's kind
  ("screenshot of..."), plus a bonus if all the words of the query appear side by side in order in one passage.
- The weight of the word part depends on the query's language, which is judged from the query's own vector
  (`engine/QueryLanguage.kt`): words count for more in languages where the meaning model is weaker.

Results appear in two steps while typing: the word answer immediately, the meaning answer a moment later
(`engine/ProgressiveSearch.kt`).

## Being polite: battery, heat and the person's hand

Indexing is a lot of work for a small phone, and Trawl is meant to be invisible. The rules are pure functions with unit tests, so
the whole table is checkable.

**Power tiers** (`engine/IndexPolicy.kt`). Three levels, each including the one before:

1. Words only: names and text. Always allowed.
2. Light: adds quick look over the backlog, and full quality for files that arrived recently, a few at a time. This is what
   makes a gallery searchable by meaning without a charger.
3. Bulk: full quality over the whole backlog. On the charger by default (or on battery if the person turns that on).

On battery the level falls with the charge: under 30% only words run. A warm phone keeps quick look but holds full quality
back, and a hot phone runs words only. Work resumes by itself when the phone cools or charges. Audio and video wait for the
charger unless the person chose otherwise.

**Polite mode** (`engine/PolitePolicy.kt`, `engine/UserYield.kt`). If the screen is on and Trawl is not in front, every heavy
stage waits. Measurements showed that even a low-priority background job makes the launcher and the next app start slower, so
nothing is half-run. After 30 seconds of waiting the models are released so the app in front has the memory. With Trawl in
front, or with the screen off (night, charger), indexing runs at full speed. Inside a single file, work also pauses between
expensive steps while the person scrolls or types.

**Pacing** (`engine/Pace.kt`). The progress meter's "time left" comes from what this phone is actually doing, per kind of file,
starting from a pessimistic default.

**Calm scheduling** (`engine/ScheduleGate.kt`). Requests to start indexing are coalesced so a burst of file-change
notifications does not become a storm of jobs.

## Memory

Phones with 2 to 4 GB of RAM are the target. The big models are loaded only while there is work and are released when the phone is
in someone's hand (`engine/MemoryProfile.kt`). A lite model set exists for 2 GB phones, and an edition for 32-bit Android Go
phones (Trawl Go) that has no meaning model at all and works from names and words only.

## Privacy

- No internet permission; the build has a check for it (`scripts/check-no-internet.sh`).
- The index sits in the app's private storage. "Delete everything" overwrites the vector file before removing it.
- Optional protection for private-looking pictures: a small classifier blurs previews, and an app lock can hide them
  (`privacy/`).
- Crash reports are made on the phone from counts and error names, never file names, searches or text. The person reads the
  report and sends it from their own email app.

## Model delivery

The models are not in the APK and not in git. They are one signed zip: a manifest signed with an ECDSA P-256 key, listing every
file with its size and SHA-256 (`engine/SignedBundle.kt`, `engine/ModelDelivery.kt`). Because the app cannot reach the internet,
the person downloads the zip in a browser (or copies it from another device); Trawl finds it in Downloads, checks the signature
and every checksum, and only then installs it. A failed check leaves the installed models untouched. The matching public keys are
compiled into the app; release builds refuse unsigned model files. `scripts/pack-bundle.sh` builds and signs a bundle.

## Two editions

`src/main` is shared. `src/full` is Trawl (arm64, meaning search, speech, quick look). `src/go` is Trawl Go (32-bit, words only,
no native model libraries). `engine/Edition.kt` is the switch.
