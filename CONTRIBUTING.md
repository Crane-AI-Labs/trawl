# Contributing to Trawl

Thank you for helping build Trawl — local-first, zero-network semantic search for your own files.

## Prerequisites

- **JDK 21** (Temurin 21.0.8 or newer recommended)
- **Python 3.9+** (for `scripts/garden-lint.py`)
- **macOS (Apple Silicon)** for Desktop macOS native helpers (`clang`, `swiftc`, Xcode Command Line Tools)
- **Android SDK (API 36)** only if building `:app`

## 3-Command Local Build & Test

1. **Run the shared engine tests (`:core`):**
   ```bash
   ./gradlew :core:jvmTest --no-daemon
   ```

2. **Run Desktop tests & release gates (`:desktop`):**
   ```bash
   cd desktop
   ../gradlew -Dorg.gradle.jvmargs=-Xmx2g -Dkotlin.daemon.jvmargs=-Xmx2g \
     test checkJvmClassfileVersion checkNoTelemetryInNatives --no-daemon
   ```

3. **Run the fast architecture & security linter (`G1`–`G29`, <200 ms):**
   ```bash
   python3 scripts/garden-lint.py --self-test && python3 scripts/garden-lint.py
   ```

*(For Android `:app`: `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:testFullDebugUnitTest :app:assembleFullRelease --no-daemon`.)*

## Non-Negotiable Engineering Invariants

Every PR must preserve these guarantees (most are enforced automatically by `scripts/garden-lint.py`):

- **D43 Zero-Network / Zero-Telemetry (`G2`):** Production code in `:core`, `:app`, and `:desktop` must never open sockets, HTTP clients, or analytics endpoints. External links (such as Settings → *Check for updates*) may only hand off to the user's system browser on an explicit click.
- **Zero Personal Paths or Secrets (`G28`):** Never commit developer home paths, usernames, emails, device serials, API keys, or local Mach-O install paths (`-install_name @rpath/...` is enforced for macOS dylibs).
- **Schema Compatibility:** Never alter SQLite `Schema.VERSION` (`7L` on Desktop, `8L` on Android) or break existing on-disk `vectors.bin` / `index.db` upgrades without a tested migration.
- **Non-Destructive Index Removal (`G27`):** Removing a file or folder from Trawl's index must never delete or modify user files on disk.
- **macOS AppKit / AWT Safety (`G17`, `G29`):** Never use `dispatch_sync(dispatch_get_main_queue(), ...)` in `desktop/native/trawl-mac.m` (AB-BA deadlock with `CAccessibility -> LWCToolkit.invokeAndWait`). Keep Quick Panel focus routed to Skiko's `HardwareLayer` (`java.awt.Canvas`) and verified against real OS `isKeyWindow`.
- **Accessibility & 14-Language Parity:** All UI contrast must meet WCAG AA (`>= 4.5:1` for text/chips, `G24`). New English string keys belong in `desktop/src/main/resources/trawl/strings.properties` and must pass `LanguagesTest`.
- **Zero Compiler Warnings:** Keep Kotlin production compilation warning-free and `desktop/native/trawl-mac.m` clean under `clang -fobjc-arc -Wall -Wextra -Werror`.

## Pull Request Checklist

- [ ] `python3 scripts/garden-lint.py --self-test && python3 scripts/garden-lint.py` passes with `0` violations
- [ ] Relevant unit tests added/updated and passing (`:core:jvmTest`, `:desktop:test`, or `:app:testFullDebugUnitTest`)
- [ ] Zero new Kotlin production warnings (and strict `clang -Wall -Wextra -Werror` if `trawl-mac.m` changed)
- [ ] Zero personal paths, secrets, or binary artifacts in `git diff`
- [ ] For Desktop packaging changes: `createDistributable`, `checkLauncherHeap`, `checkPackagedRuntime`, `checkJvmClassfileVersion`, `checkNoTelemetryInNatives`, and (macOS) `checkMacPrivacyStrings` all pass
