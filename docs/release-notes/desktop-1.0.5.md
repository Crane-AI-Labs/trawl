# Trawl desktop 1.0.5: developer preview, unsigned

**This is a developer preview.** The installers are **not signed**: they carry no Apple Developer ID or notarisation, no Windows
code-signing certificate, and no package signature. Your computer will warn you. Install one only if you are comfortable with that,
and check the download against the checksums below first. The phone app (Android) is the supported release.

This replaces desktop-preview-1.0.4 (and 1.0.3 and older). Install it over the old one: your index and settings stay.

Trawl on a computer finds the files in your folders by describing them, the way the phone app does: documents, photos and
screenshots (and the words in them), video and audio. It runs entirely on the computer and makes no network connections.

| Installer | For | Size |
|---|---|---|
| `Trawl-1.0.5.dmg` | Mac with Apple silicon (M1 or newer), macOS 13 or newer | to confirm on the Mac |
| `Trawl-1.0.5.msi` | 64-bit Windows 10 or 11 | to confirm at build |
| `trawl_1.0.5_amd64.deb` | Linux x86-64 (Debian, Ubuntu and relatives) | about 227 MB |

## What's new since 1.0.4

**Typing a question and pressing Enter now opens the best match.** Trawl shows the files that contain your words first, and a moment
later re-orders the list once it has understood what you meant. Until now the highlighted row (the one Enter opens, the one shown in
the preview) stayed on the first words-only match, so after the list re-ordered you could be looking at the right file at the top
while Enter opened a weaker one, and the list could scroll so the best match was out of sight. The highlight now follows the best match
until you choose a row yourself (an arrow key, a click, Select all) or scroll away from the top; after that it stays where you put it.

**A question typed while Trawl is starting gets meaning too.** Its words-only answer refreshes once when the search engine is ready, without flashing back to words; the highlight follows the best match unless you have chosen a row or scrolled away.

Nothing else changed in the program. The 1.0.4 background-reading changes, the language additions and the known limits below are as in 1.0.4.

## What computer you need
Unchanged from 1.0.4: Windows 10 or 11, Ubuntu 24.04 and relatives, or a Mac with Apple silicon and macOS 13 or newer.

## Install
As for 1.0.4: download the installer for your computer and `SHA256SUMS`, check it, open it, and keep `trawl-models.zip` (about 670 MB, from the
[`models-v1` release](https://github.com/Crane-AI-Labs/trawl/releases/tag/models-v1)) in your Downloads folder. Already on 1.0.1 or newer? You have the models.
Linux: `sudo apt install ./trawl_1.0.5_amd64.deb`; video and recordings need `ffmpeg`, the words in photos need `tesseract-ocr`.

## What was checked
The 931 desktop tests on our Linux build computer: 0 failed, 0 errors, 30 skipped (specific to other systems). One new test reproduces the
bug above and fails on 1.0.4's rule. In the installed Linux app (the .deb built from this code, over a 1.0.4 index of 205 files), three
searches that opened the wrong file under 1.0.4 open the best match, with the program that opens files replaced by one that records which
file it was asked to open. The no-network check and the Windows and Mac checks of 1.0.4 were not repeated: only the search list's selection
rule and the version number changed. They run again on the installers before they are published.

## Known limits of this preview
Unchanged from 1.0.4: unsigned; manual updates; slower background reading on a Mac while Trawl's window is not in front; spoken-word search
in recordings is English only, as on the phone; Apple silicon Macs only; Windows 10 Share, Windows N editions, Ubuntu on Wayland and
server images as listed in the 1.0.4 notes.

## Checksums (SHA-256)

```
(dmg line added on the Mac)  Trawl-1.0.5.dmg
(msi line added at build)    Trawl-1.0.5.msi
a18c6965122dd83f99c61669e994f3f28cd54eb4bf2f50d2b5d979cf4408f2d0  trawl_1.0.5_amd64.deb   (test build, to be rebuilt at release)
```

Licence: Apache License 2.0. Source: https://github.com/Crane-AI-Labs/trawl
