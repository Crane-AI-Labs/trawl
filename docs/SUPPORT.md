# What Trawl supports

Legend: **works** (measured on a phone) · **partly** (works with a stated limit) · **not yet**. The format follows
OHS kotlin-fhir-engine's conformance pages. Every row says where the evidence is. Updated 2026-10-15.

## Files
| Kind | Status | What is searchable | Evidence |
|---|---|---|---|
| Photos (jpg, png, webp, heic/heif, bmp, avif) | **works** | meaning (EmbeddingGemma image vector) and any text in the picture (OCR) | eval-en; messy corpus (HEIC, webp, EXIF-rotated) graded on the Infinix |
| Screenshots | **works** | meaning and OCR text | eval-en screenshot set |
| Animated GIF | **partly** | first frame only | messy corpus |
| SVG, DNG, PSD | **not yet** | file name only | `index/Rules.kt` |
| Videos | **works** | key frames (meaning), on-screen text, speech (English) and sound labels, by time | A06 in-app runs: 36.4 phone-s per video minute with sound |
| Voice notes and audio | **partly** | English speech and sound labels; other languages get sound labels and the audio vector, not a transcript | speech language gate |
| Text, Markdown, CSV, JSON, logs, HTML, RTF | **works** | words and meaning; binary data with a text name is skipped; a PDF named .txt is read as a PDF | messy corpus; `TextTest` |
| Word, Excel, PowerPoint (docx, xlsx, pptx), OpenDocument (odt, ods, odp), EPUB | **works** | words and meaning | `OfficeTextTest`; messy corpus |
| Legacy Office (doc, xls, ppt) | **not yet** | file name only | messy corpus |
| PDF with a text layer | **works** | words and meaning per page; long files are read in steps, with every word searchable after the first step | messy corpus (592-page report) |
| Scanned PDF (images only) | **partly** | OCR of the first 50 scanned pages | 15-page scan test on the Infinix |
| Password-protected PDF | **not yet** | file name only | messy corpus |
| Archives, APKs, databases | name only, by design | file name only | messy corpus |
| App caches, `.nomedia` folders, trash, thumbnails | skipped by design | nothing | messy corpus |

## Languages (search by meaning)
| Language | Status | Measured | Evidence |
|---|---|---|---|
| English | **works** | same-language P@1 0.81; eval-en r@1 0.82 | multilingual eval |
| Swahili | **partly** | P@1 0.53; Swahili↔English bitext 0.90 | multilingual eval |
| Luganda | **partly**, weak | P@1 0.26; works mainly when names or numbers match | multilingual eval |
| Runyankole, Acholi, Ateso, Lugbara, Kinyarwanda | **not yet measured** | fine-tune and evaluation in progress | |

Keyword search (exact words) works in every language. Speech to text is English only; non-English speech is detected
and not transcribed (English kept 98%, Luganda junk let through 4%, Swahili 20%).

## Add-ons (optional model packs)
Trawl never connects to the internet, so an add-on is a .zip you copy into the phone's Downloads folder, then add from
Settings → Add-ons (or from the "Add extras?" step when you first open the app). Trawl checks every file (size and
SHA-256) before using it.
| Add-on | Status | What it gives | Size | File to put in Downloads |
|---|---|---|---|---|
| Speech search | **works** | right file first 81% of the time instead of 40% for what was said; about 13 s extra per video minute while reading; English only | 142 MB | `findr-speech-models.zip` |
| Quick look for photos | **works** (D2; model TinyCLIP since D10) | every photo, screenshot and video searchable by meaning within minutes, then full detail fills in; English searches only (others wait for full detail). 1,000 camera photos on a Galaxy A06: 95% within about 7 min (release build) instead of about 87; it slows full detail by about 5% while both run | 117 MB | `findr-quicklook-models.zip` |

| Blur private-looking previews | **works** | blurs the small preview of a photo that looks private (nudity, underwear), in the quick-search panel (on by default) and optionally in the main app (off by default); tap the eye to reveal, and in the panel that needs the phone unlocked. Blur only: results are never hidden or reordered | 22 MB | `findr-private-models.zip` |

Blur private-looking previews is recommended for anyone who uses the quick-search panel, which opens over other apps. It is an
automatic classifier at a high threshold, so it can be wrong both ways. **Health workers: photos that show skin for a clinical
reason (a rash, a wound, a breastfeeding mother, an examination) can be blurred, and an indecent picture can be missed.** A blurred
preview is only a convenience; the file is still found, ranked where it belongs, and opens, shares and copies as usual. Without the add-on
nothing is blurred. It reads photos only while charging, at a low priority, and slows full reading by about 5% while both run
(Galaxy A06, measured).

Remove deletes the add-on's files and turns it off, which frees its size. Words and meaning already learned from your
files stay until the files are read again. After a Remove or "Not now" Trawl does not add the pack back by itself.
On the Play Store the same button will fetch the pack from Play instead of Downloads.

## Phones
| Phone | Status | Measured |
|---|---|---|
| Galaxy A06 (Helio G85, 4 GB) | **works** | the reference phone for every speed number |
| Infinix X6885 (Helio G99, 8 GB) | **works** | about 2× the A06 |
| 2 GB phones, Android Go (lite mode) | **partly**: measured on a 2 GB low-RAM emulator, not yet on a 2 GB phone | see below |

### 2 GB phones: lite mode
Phones with less than about 2.6 GB of memory, or Android Go phones, run Trawl in **lite mode**, chosen automatically. Phones
with 3 GB or more are not affected. In lite mode:
- **Documents**: the same search as on bigger phones (the same text model).
- **Photos, screenshots, memes and video frames** are read by the **Quick look** add-on, and any text in them is understood
  by meaning too. In English, search is as good as on bigger phones on our test set (right file first 90% of the time).
  Without the Quick look add-on, photos without text are found by name and date only, so lite mode works best with it.
- **Searches in Luganda or Swahili** find photos less well than on bigger phones (Quick look reads English only); pictures
  with text in them are still found by their text.
- **Speech search** is off at first; adding the Speech search add-on turns it on (English only, about 250 MB more memory
  while it reads).
- **Voice notes without the Speech search add-on are found by name and date only.** Bigger phones can also match voice notes
  by sound; that needs the large model file (485 MB instead of 165 MB) and about 190 MB more memory, so lite mode leaves it out.
- Memory (measured on a 2 GB emulator, not yet on a 2 GB phone): Trawl uses about 300 MB while it first reads a phone and
  up to about 600 MB while it understands files. The full mode used about 830 MB reading photos there, and about 1.1 GB
  with all its models at once, which made the emulator close other apps to make room.
