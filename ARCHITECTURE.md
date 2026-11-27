# Trawl Architecture

Trawl is a **local-first, zero-network** semantic and keyword search engine across Desktop, Android, Web, and iOS. Every platform follows the same core principles: **zero outbound network code (D43)**, on-device embeddings and OCR/ASR, off-heap memory-mapped vector search, and the **Sunlight** Light/Dark design system (`#FFFBF2` / `#15130E`, Atkinson Hyperlegible, WCAG AA contrast).

## Module & Repository Map

| Module / Repo | Platform | Tech Stack | Role |
|---|---|---|---|
| `:core` | Shared (JVM / Android / Desktop) | Kotlin, SQLite FTS5, C NEON SDOT | Hybrid `Searcher` (BM25 + SQ8 v3 off-heap `VectorIndex`), `DocFormats`, `DateParser`, `LocalKeywords` (Luganda/Swahili glosses), `Schema` |
| `:desktop` | macOS, Windows, Linux | Compose Multiplatform Desktop, JDK 21 (pinned Temurin), Obj-C/Swift native helpers | Full window + Spotlight-style Quick Panel, `IndexService`, `DesktopAsr` (Parakeet), `PdfThumbs`, `StructuredPreview` (`PROSE`/`JSON`/`TABLE`/`CODE`), native drag/copy/hotkey (`trawl-mac.m`) |
| `:app` | Android (API 30–36) | Kotlin, Jetpack Compose, LiteRT-LM, Sherpa-ONNX | MediaStore + folder indexing, EmbeddingGemma + MobileCLIP + Parakeet ASR, zero `INTERNET` permission, thermal/battery pacing (`D51`) |
| `web-trawl-kmp` | Web UI | Kotlin/Wasm (`wasmJs`), Compose Multiplatform | Shared Sunlight UI, `PROSE`/`StructuredPreview`, `Find similar` + scroll restore, zero-leak KLIB/Webpack production bundle |
| `web-trawl` | Web Runtime & Bridge | Vanilla ES Modules, OPFS/IndexedDB, vendored `pdfjs-dist` | Cross-Browser Baseline (Chrome, Firefox, Safari) file ingest, local worker/Wasm bridge, COOP/COEP/CORP/CSP headers, pre-deploy leak gate |
| `ios-trawl` | iOS (`iosArm64` / Simulator) | Compose Multiplatform iOS + Swift/Obj-C bridges | `UIDocumentPickerViewController`, `PHPhotoLibrary`, `Vision` OCR, `PDFKit`, `AVFoundation`, Sunlight UI parity |
| `trawl-site` | Marketing Site | Static HTML/CSS/JS | Self-contained, zero-CDN, zero-`'unsafe-inline'` CSP site gated by `scripts/site-lint.py` |

## Search & Storage Pipeline (`:core` + `:desktop`)

```text
User Folders & Added Files
  ├─► Crawler (java.nio, hidden/dependency filters, Scope exclusions, LargeFolderDetector)
  │     └─► SQLite WAL (index.db: files + FTS5 porter index, Schema.VERSION = 7L)
  ├─► Readers (PDFBox, Office XML/XLS, Apple Vision / OS OCR, MediaDecoder + Parakeet ASR)
  └─► Embedder (LiteRT-LM EmbeddingGemma 2)
        └─► VectorIndex (vectors.bin v3 SQ8 memory-mapped off-heap + libtrawl_vec_neon.dylib SDOT)

Query / Find Similar
  └─► Two-Stage Searcher
        ├─ Stage 1: Instant FTS5 keyword + filename hits (words-first)
        └─ Stage 2: Off-heap SQ8 cosine search + z-score fusion + snippet/moment locators
```

## Desktop Native & OS Architecture (`desktop/`)

- **Zero-Deadlock macOS Bridge (`desktop/native/trawl-mac.m`):** Uses `dispatch_async` exclusively (never `dispatch_sync(main_queue)`), in-process Carbon `RegisterEventHotKey`, `SwingWindow` (`Window.Type.UTILITY` -> `AWTWindow_Panel`) floating over Full-Screen Spaces, lock-free atomic `isKeyWindow` tracking + Skiko `HardwareLayer` canvas focus routing, native `NSDraggingSession` (`NSURL` / `public.file-url`), and `@rpath` install names (`-install_name @rpath/...`).
- **Politeness & Memory Governance:** Shipped launcher uses percentage-based heap (`-XX:MaxRAMPercentage=35 -XX:MaxRAM=12g`) plus JEP 346 G1GC idle heap return (`G1PeriodicGCInterval=30000`, `MinHeapFreeRatio=10`, `MaxHeapFreeRatio=25`), macOS `PRIO_DARWIN_BG` background throttling, thermal pre-throttling, and idle ASR model release.
- **Privacy & Security Gates:** Pinned ONNX Runtime `1.28.0` checked by `checkNoTelemetryInNatives`, JVM bytecode checked `<= JDK 21` by `checkJvmClassfileVersion`, macOS Info.plist privacy strings verified by `checkMacPrivacyStrings`, and `scripts/garden-lint.py` (`G1`–`G29`) enforcing all architectural invariants in `<200 ms`.
