# Trawl desktop 1.0.4: developer preview, unsigned

**This is a developer preview.** The installers are **not signed**: they carry no Apple Developer ID or notarisation, no Windows
code-signing certificate, and no package signature. Your computer will warn you. Install one only if you are comfortable with that,
and check the download against the checksums below first. The phone app (Android) is the supported release.

This replaces desktop-preview-1.0.3 (and 1.0.2, 1.0.1 and 1.0.0). Install it over the old one: your index and settings stay.

Trawl on a computer finds the files in your folders by describing them, the way the phone app does: documents, photos and
screenshots (and the words in them), video and audio. It runs entirely on the computer and makes no network connections.

| Installer | For | Size |
|---|---|---|
| `Trawl-1.0.4.dmg` | Mac with Apple silicon (M1 or newer), macOS 13 or newer | about 240 MB (to confirm on the Mac) |
| `Trawl-1.0.4.msi` | 64-bit Windows 10 or 11 | 228 MB |
| `trawl_1.0.4_amd64.deb` | Linux x86-64 (Debian, Ubuntu and relatives) | 227 MB |

## What's new since 1.0.3

**Trawl now shares the computer with you.** Reading your files used to run at one pace whatever else the computer was doing. Now:
- **It gives way to your own work.** After each file, Trawl looks at how busy the computer is and rests long enough to leave room for
  you: one processor core is always kept free for you, and while you are using the computer the Gentle and Balanced speeds take
  under one core on average. When you step away it reads faster again. It never stops altogether: however busy the computer is,
  the index still grows, slowly.
- **On a Mac and on Windows, Trawl steps behind your programs when its window is not in front.** While no Trawl window is showing
  (Gentle), or while you are working in another program (Balanced), the system is told to run Trawl after everything else
  (macOS: the background class with slow disk access; Windows: below-normal priority and "efficiency mode"). The moment you bring a
  Trawl window or the quick search forward, it is back to normal. The Fast speed is never held back. On Linux this is off.
- **On a Mac this makes background reading much slower, on purpose.** In our tests on an Apple M5, with Trawl's window not in front,
  Balanced read about 2,600 files in ten minutes instead of about 18,000: about seven times slower, with the fan silent throughout,
  against about 2,000 RPM without it. It does not use less processor time per minute; the work just runs on the Mac's slow, cool cores.
  So a first index of a big Mac takes much longer if you leave Trawl in the background. If you want it done sooner, keep the Trawl
  window open and in front, or choose Fast.
- **Balanced now uses two processor threads** (1.0.3 left it to the engine, which used four). That keeps any burst of reading to a
  couple of cores on every computer. The cost, measured against 1.0.3 on an 8-core test computer with you away and the charger
  plugged in: documents are read about a third slower (30 a minute instead of 45); photos and PDFs as fast as before.

**New reasons Trawl waits, each with a button to carry on:**
- **The battery is low.** At every speed, Trawl stops reading while the computer is on its battery below 20%, and carries on when you
  plug in. "Read on battery anyway" carries on now (until the next time the charger is plugged in). 1.0.3 had no such rule.
- **The computer is hot.** When the system reports serious heat, Trawl waits until it cools. "Run anyway" carries on.
- **The computer is saving power.** While Low Power Mode (Mac), Battery Saver (Windows) or the power-saver profile (Linux) is on,
  Trawl waits. "Run anyway" carries on. (Gentle still waits for the charger, as before.)

**Smaller changes:**
- A new or changed file is found by its name in under a second (1.0.3: about two seconds).
- A very large folder that the system cannot watch for changes is looked through less often, so watching it costs under one
  processor-second a minute instead of several.
- A paused Trawl no longer counts its index for a "new model" line the computer never shows.
- Luganda, Swahili, Runyankole, Kinyarwanda and Acholi searches also find English files, through the same word table as the phone
  app (1.0.6 and 1.0.7).
- The new messages are translated into all 13 languages (by machine, awaiting review by native speakers, like the rest).
- Also from the phone app's code since 1.0.3: changes to speech reading and add-ons, and the words shown on screen in videos.

## What computer you need
Unchanged: Windows 10 or 11, Ubuntu 24.04 and relatives, or a Mac with Apple silicon and macOS 13 or newer. It ran well on
2 cores and 8 GB of memory, which was the smallest tried. About 1 to 1.5 GB of disk with the models.

## Install
1. Download the installer for your computer, and `SHA256SUMS`. Check it (Mac and Linux: `shasum -a 256 -c SHA256SUMS --ignore-missing`;
   Windows PowerShell: `Get-FileHash .\Trawl-1.0.4.msi -Algorithm SHA256`, and compare with the line below).
2. Open it:
   - **Mac:** open the dmg and drag Trawl to Applications. The first time, **right-click Trawl and choose Open**, then Open again.
     On macOS 15 or newer, if there is no Open button: try to open Trawl once, then go to System Settings, Privacy & Security, and
     choose **Open Anyway** next to the Trawl message.
   - **Windows:** double-click the msi. When Windows SmartScreen says "Windows protected your PC", choose **More info**, then **Run anyway**.
   - **Linux:** `sudo apt install ./trawl_1.0.4_amd64.deb`. Reading **video and recordings needs ffmpeg** (`sudo apt install ffmpeg`);
     reading the words in photos needs `tesseract-ocr`. Without them Trawl still finds documents and photos.
3. Trawl needs its search models, one file. Download `trawl-models.zip` (about 670 MB) from the
   [`models-v1` release](https://github.com/Crane-AI-Labs/trawl/releases/tag/models-v1) and keep it in your Downloads folder. Trawl
   finds it, checks its signature and checksums, and installs it. (Or use Settings, "Choose trawl-models.zip".) The desktop app never
   downloads anything itself. Already on 1.0.1 or newer? You have the models; skip this step.
4. Choose the folders Trawl may read. It never changes, moves or uploads your files.

## What was checked
All tests passed on our Linux build computer: of the 926 desktop tests, 897 passed and 29 (specific to other systems) were skipped;
none failed. The phone app's and shared code's tests passed too (1,167 and 1,148 for the two phone editions, 810 and 808 for the shared
code). The build checks that no native library carries Microsoft's telemetry client and that the bundled Java is current (21.0.12.1).
On Windows we also clicked through the installed app: searching, the filters, Settings, minimising it and switching to another program
and back (and read from outside that Trawl's priority went down and came back each time).

**No network connections, checked on each system.** Each installer was installed on a fresh test computer and run through a scripted
ten-minute session that uses every part of the app (the models, the Privacy pack, documents, photos, words in pictures, video and
speech, searches, the quick panel, Settings) while every connection by Trawl and by any program it starts was watched. None was made:
on Windows (Windows Server 2022) and on Linux (Ubuntu 24.04, twice: with and without the optional programs for words in photos and for
power profiles, which 1.0.4 now asks about). A test connection made on purpose before each run was seen every time, so the watcher was
not blind. On a Mac the same check runs on the dmg before it is published.

## Known limits of this preview
- Unsigned (above). Updates are manual: install the newer installer over the old one.
- On a Mac, background reading is much slower while Trawl's window is not in front (above).
- Spoken-word search in recordings is English only, as on the phone.
- Mac builds are Apple silicon only; there is no Intel Mac, Windows on ARM or Linux ARM installer yet.
- Windows 10: Share opens nothing; use Show in folder. Windows 11 is fine.
- Windows N and KN editions: install Microsoft's Media Feature Pack first, or video and audio can't be read.
- Ubuntu on Wayland (the default): there is no tray icon and no global shortcut. To open the quick search with a key, add a custom
  keyboard shortcut in Settings that runs `/opt/trawl/bin/Trawl --quick`. On an Xorg session both work.
- Linux without a desktop (a server image): the package's menu step fails ("No writable system menu directory found"). Run
  `sudo mkdir -p /etc/xdg/menus /usr/share/applications /usr/share/desktop-directories` and then `sudo dpkg --configure trawl`.
  The same was true of 1.0.3; a normal Ubuntu desktop is not affected.

## Checksums (SHA-256)

```
(dmg line added on the Mac)  Trawl-1.0.4.dmg
4cb1f633f8826619d0dfaa00cc328025fdd1554ef1de8376f7490a6a6a210c61  Trawl-1.0.4.msi
a884dc836dc82a76f43802da490a1d1c2fc51ff2bc958982ddee86b1689d9fdf  trawl_1.0.4_amd64.deb
```

Built from the desktop code in commit `f6e3542` of this repository.

Licence: Apache License 2.0. Source: https://github.com/Crane-AI-Labs/trawl
