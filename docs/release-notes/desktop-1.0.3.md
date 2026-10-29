# Trawl desktop 1.0.3: developer preview, unsigned

**This is a developer preview.** The installers are **not signed**: they carry no Apple Developer ID or notarisation, no Windows
code-signing certificate, and no package signature. Your computer will warn you. Install one only if you are comfortable with that,
and check the download against the checksums below first. The phone app (Android) is the supported release.

This replaces desktop-preview-1.0.2 (and 1.0.1 and 1.0.0). Install it over the old one: your index and settings stay.

Trawl on a computer finds the files in your folders by describing them, the way the phone app does: documents, photos and
screenshots (and the words in them), video and audio. It runs entirely on the computer and makes no network connections.

| Installer | For | Size |
|---|---|---|
| `Trawl-1.0.3.dmg` | Mac with Apple silicon (M1 or newer), macOS 13 or newer | 240 MB |
| `Trawl-1.0.3.msi` | 64-bit Windows 10 or 11 | 228 MB |
| `trawl_1.0.3_amd64.deb` | Linux x86-64 (Debian, Ubuntu and relatives) | 227 MB |

## What's fixed since 1.0.2
- **Trawl stays quiet on a computer with a very large index.** With an index of well over a hundred thousand files, 1.0.2 could still
  keep a processor busy (45 to 80 percent of one) even while paused, because it recounted its whole index several times a second just
  to update the progress meter. Now:
  - The progress meter is updated at most once a second, and counts are kept from one change to the next instead of being counted again.
    The one count that is left reads a small new list, so it is about ten times quicker.
  - **Paused really is quiet.** While Trawl is paused it looks for no work and recounts nothing. On our largest test index (180,000 files)
    it used 0.04 processor-seconds a minute paused and 0.9 at rest, against 23 for both before.
  - **Old leftovers are cleared steadily.** When files or folders drop out of the index (for example dependency folders that are skipped now),
    Trawl forgets them in small batches that give way to your searches, and it eases off while paused. It should no longer stop part-way.
- The first start after the update adds one small list to the index (a second or two on a big index). Your index and settings stay.

## What computer you need
Unchanged from 1.0.1: Windows 10 or 11, Ubuntu 24.04 and relatives, or a Mac with Apple silicon and macOS 13 or newer. It ran well on
2 cores and 8 GB of memory, which was the smallest tried. About 1 to 1.5 GB of disk with the models.

## Install
1. Download the installer for your computer, and `SHA256SUMS`. Check it (Mac and Linux: `shasum -a 256 -c SHA256SUMS --ignore-missing`;
   Windows PowerShell: `Get-FileHash .\Trawl-1.0.3.msi -Algorithm SHA256`, and compare with the line below).
2. Open it:
   - **Mac:** open the dmg and drag Trawl to Applications. The first time, **right-click Trawl and choose Open**, then Open again.
     On macOS 15 or newer, if there is no Open button: try to open Trawl once, then go to System Settings, Privacy & Security, and
     choose **Open Anyway** next to the Trawl message.
   - **Windows:** double-click the msi. When Windows SmartScreen says "Windows protected your PC", choose **More info**, then **Run anyway**.
   - **Linux:** `sudo apt install ./trawl_1.0.3_amd64.deb`. Reading **video and recordings needs ffmpeg** (`sudo apt install ffmpeg`);
     reading the words in photos needs `tesseract-ocr`. Without them Trawl still finds documents and photos.
3. Trawl needs its search models, one file. Download `trawl-models.zip` (about 670 MB) from the
   [`models-v1` release](https://github.com/Crane-AI-Labs/trawl/releases/tag/models-v1) and keep it in your Downloads folder. Trawl
   finds it, checks its signature and checksums, and installs it. (Or use Settings, "Choose trawl-models.zip".) The desktop app never
   downloads anything itself. Already on 1.0.1? You have the models; skip this step.
4. Choose the folders Trawl may read. It never changes, moves or uploads your files.

## What was checked
The fix was measured on a test computer with a made-up index of 180,000 files and 1.5 million text pieces (729 MB). At rest it used
0.87 processor-seconds a minute (before: 23.4), paused 0.04 (before: 23.0), and a full rescan did 24 progress updates instead of 262. Search
results were not changed: nothing in the search path was touched. Of the 863 desktop tests, 828 passed and 34 (specific to other systems) were skipped on the test computer. The one that did not pass
is a screen-test of the quick panel's Clear button; it fails the same way without this change when it runs after the others, and passes when
it runs alone (it did here). The installers are built the same
way as 1.0.2; the zero-network test of 1.0.1 covers code that this release does not change.

## Known limits of this preview
- Unsigned (above). Updates are manual: install the newer installer over the old one.
- Spoken-word search in recordings is English only, as on the phone. Luganda and Swahili text search is still being measured.
- Mac builds are Apple silicon only; there is no Intel Mac, Windows on ARM or Linux ARM installer yet.
- Windows 10: Share opens nothing; use Show in folder. Windows 11 is fine.
- Windows N and KN editions: install Microsoft's Media Feature Pack first, or video and audio can't be read.
- Ubuntu on Wayland (the default): there is no tray icon and no global shortcut. To open the quick search with a key, add a custom
  keyboard shortcut in Settings that runs `/opt/trawl/bin/Trawl --quick`. On an Xorg session both work.

## Checksums (SHA-256)

```
244c1e4cc785020bc97a8e8bbd1280c9b4109c00947d1831cce430be9f071a17  Trawl-1.0.3.dmg
979a55e1dd0dec7ee51d2c46c27a2c7e5900f55d05535974318cf3aeec378116  Trawl-1.0.3.msi
9d84942bcdb9b0801af9cf3abc074d92f953d5db4d10002185b8bdfc6c943d70  trawl_1.0.3_amd64.deb
```

Built from the desktop code in commit `958dc10` of this repository.

Licence: Apache License 2.0. Source: https://github.com/Crane-AI-Labs/trawl
