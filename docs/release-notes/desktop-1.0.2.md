# Trawl desktop 1.0.2: developer preview, unsigned

**This is a developer preview.** The installers are **not signed**: they carry no Apple Developer ID or notarisation, no Windows
code-signing certificate, and no package signature. Your computer will warn you. Install one only if you are comfortable with that,
and check the download against the checksums below first. The phone app (Android) is the supported release.

This replaces desktop-preview-1.0.1 (and 1.0.0). Install it over the old one: your index and settings stay.

Trawl on a computer finds the files in your folders by describing them, the way the phone app does: documents, photos and
screenshots (and the words in them), video and audio. It runs entirely on the computer and makes no network connections.

| Installer | For | Size |
|---|---|---|
| `Trawl-1.0.2.dmg` | Mac with Apple silicon (M1 or newer), macOS 13 or newer | 240 MB |
| `Trawl-1.0.2.msi` | 64-bit Windows 10 or 11 | 228 MB |
| `trawl_1.0.2_amd64.deb` | Linux x86-64 (Debian, Ubuntu and relatives) | 227 MB |

## What's fixed since 1.0.1
- **Trawl no longer chews the processor on a computer with code folders.** If you keep programming projects in your Documents, Pictures
  or Downloads, 1.0.1 could run at 35 to 70 percent of a processor for days. Two causes are gone:
  - A TypeScript file (`.ts`) is no longer taken for a video. Trawl looks inside the file now, and only a real video is opened as one.
    Folders that tools fill with other people's code (`node_modules`, build and cache folders and similar) are skipped. A folder you
    named yourself is never skipped, and a folder called "build" or "target" with photos in it is still read.
  - **Pause now really pauses.** While Trawl is paused it no longer walks your folders in the background. The changes it noticed are
    caught up once, when you resume. Choosing folders or asking for a rescan still works while paused, because you asked.
- On the first start, Trawl quietly removes the entries it made by mistake for those `.ts` files. Your index of real files stays.

## What computer you need
Unchanged from 1.0.1: Windows 10 or 11, Ubuntu 24.04 and relatives, or a Mac with Apple silicon and macOS 13 or newer. It ran well on
2 cores and 8 GB of memory, which was the smallest tried. About 1 to 1.5 GB of disk with the models.

## Install
1. Download the installer for your computer, and `SHA256SUMS`. Check it (Mac and Linux: `shasum -a 256 -c SHA256SUMS --ignore-missing`;
   Windows PowerShell: `Get-FileHash .\Trawl-1.0.2.msi -Algorithm SHA256`, and compare with the line below).
2. Open it:
   - **Mac:** open the dmg and drag Trawl to Applications. The first time, **right-click Trawl and choose Open**, then Open again.
     On macOS 15 or newer, if there is no Open button: try to open Trawl once, then go to System Settings, Privacy & Security, and
     choose **Open Anyway** next to the Trawl message.
   - **Windows:** double-click the msi. When Windows SmartScreen says "Windows protected your PC", choose **More info**, then **Run anyway**.
   - **Linux:** `sudo apt install ./trawl_1.0.2_amd64.deb`. Reading **video and recordings needs ffmpeg** (`sudo apt install ffmpeg`);
     reading the words in photos needs `tesseract-ocr`. Without them Trawl still finds documents and photos.
3. Trawl needs its search models, one file. Download `trawl-models.zip` (about 670 MB) from the
   [`models-v1` release](https://github.com/Crane-AI-Labs/trawl/releases/tag/models-v1) and keep it in your Downloads folder. Trawl
   finds it, checks its signature and checksums, and installs it. (Or use Settings, "Choose trawl-models.zip".) The desktop app never
   downloads anything itself. Already on 1.0.1? You have the models; skip this step.
4. Choose the folders Trawl may read. It never changes, moves or uploads your files.

## What was checked
The fix was measured on a test computer with a made-up folder tree holding 20,821 `.ts` files and six real videos: Trawl found all
six videos and tried to open as video only 3 files, against 1,902 attempts before. With the index finished, it used
0.24 processor-seconds a minute at rest (before: it never finished, 41). Paused with 1,000 files being written, it did no folder walks
at all (before: about 50 a minute). Of the 848 desktop tests, none failed (29 are specific to other systems and were skipped on the test computer). The installers are built the same way as 1.0.1; the zero-network test
of 1.0.1 covers code that this release does not change.

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
e0647b16c4a4d6926529a2917cccf967550365944b62d97dc72fc54fe45c2db5  Trawl-1.0.2.dmg
9bed26984ba7508157654c87f6592cacb8539510238bb2b21f3ac47780e93c42  Trawl-1.0.2.msi
95ca54013df9d8f95cec5f3c1cef3405d47bdcfcd9b71fcec06ba85bd6077f80  trawl_1.0.2_amd64.deb
```

Built from the desktop code in commit `454a413` of this repository.

Licence: Apache License 2.0. Source: https://github.com/Crane-AI-Labs/trawl
