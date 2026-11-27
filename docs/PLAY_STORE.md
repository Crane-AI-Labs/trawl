# Google Play Store Release & Submission Guide (Android `1.2.0`)

This guide documents the exact build command, pre-upload verification checks, Play Console declarations, and 30-second review video script for publishing **Trawl** (`com.craneai.findr`) to Google Play.

---

## 1. Build the Official Play Store App Bundle (`.aab`)

Play Store builds disable the two sideload-only manifest permissions (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and `FOREGROUND_SERVICE_MEDIA_PROCESSING`) at compile time while keeping all offline functionality intact:

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:bundleFullRelease \
  -PbatteryExemptionRequest=false \
  -PmediaProcessingFgs=false \
  -PtrawlPublish=true \
  -PtrawlPlayAssetDelivery=true \
  --no-daemon
```

- **Output AAB:** `app/build/outputs/bundle/fullRelease/app-full-release.aab`
- **Play Asset Delivery (`-PtrawlPlayAssetDelivery=true`):**
  - Wires 8 real `com.android.asset-pack` `on-demand` modules (`pack_speech`, `pack_quicklook`, `pack_privacy`, `pack_speech_lg`, `pack_speech_nyn`, `pack_speech_ach`, `pack_speech_sw`, `pack_speech_xog`) plus `com.google.android.play:asset-delivery:2.3.0` (`PlayAssetPackSource` + `GooglePlayAssetDeliveryClient`).
  - Downloads run out-of-process via Google Play Store IPC (`com.android.vending`); Trawl itself retains **0 `android.permission.INTERNET`**.
  - Sideload APK builds omit `-PtrawlPlayAssetDelivery=true`, keeping zero Play Core library dependency and the lean ~46 MB APK. If a Play AAB is installed outside Google Play, `PlayAssetPackSource` automatically falls back to `DownloadsFolderSource` + external browser download links.
  - Before uploading to Play Console, stage the verified real weights into `asset-packs/pack_speech_<lang>/src/main/assets/` via `scripts/pack-speech-packs.sh --src <dir> --stage-pad` (never commit binary weights to git).
- **Signing:** Signed with the release keystore configured in `~/.gradle/gradle.properties` (`trawlKeystore`, `trawlKeystorePassword`, `trawlKeyAlias`, `trawlKeyPassword`, certificate `CN=Trawl, O=Crane AI Labs, C=UG`). Enroll in **Play App Signing** using this key as the upload key (or export the signing key if upgrading existing installs).
- **Embedded Play Metadata (verified inside AAB):**
  - R8 deobfuscation map: `BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map` (`app/build/outputs/mapping/fullRelease/mapping.txt`)
  - Native debug symbols (`SYMBOL_TABLE`): `BUNDLE-METADATA/com.android.tools.build.debugsymbols/arm64-v8a/` (`app/build/outputs/native-debug-symbols/fullRelease/native-debug-symbols.zip`)
  - Baseline Profile: `BUNDLE-METADATA/com.android.tools.build.profiles/baseline.prof`

---

## 2. Pre-Upload Verification Gates

Run all four checks before uploading to Play Console:

1. **Repo & privacy lint (`G1`–`G29`):**
   ```bash
   python3 scripts/garden-lint.py --self-test && python3 scripts/garden-lint.py
   ```
2. **Manifest permissions audit** (`app/build/intermediates/merged_manifest/fullRelease/processFullReleaseMainManifest/AndroidManifest.xml`):
   - `android.permission.INTERNET`: **0 occurrences** (explicitly stripped via `tools:node="remove"`).
   - `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: **0 occurrences** (omitted when `-PbatteryExemptionRequest=false`; app opens system Battery Settings instead).
   - `android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING`: **0 occurrences** (omitted when `-PmediaProcessingFgs=false`; indexing runs strictly under `dataSync`).
   - `android:allowBackup="false"` (private index never enters cloud backups).
   - `targetSdkVersion="36"`, `minSdkVersion="30"`, `versionCode="13"`, `versionName="1.2.0"`.
3. **Android 15+ 16 KB ELF Page-Size Alignment (all 10 `arm64-v8a` `.so` libraries):**
   Every native library in `base/lib/arm64-v8a/` is verified `PT_LOAD p_align == 0x4000` (16 KB) with `android:extractNativeLibs="true"`:
   - `libLiteRt.so` (`0x4000`)
   - `libLiteRtClGlAccelerator.so` (`0x4000`)
   - `libandroidx.graphics.path.so` (`0x4000`)
   - `liblitert_jni.so` (`0x4000`)
   - `liblitertlm_jni.so` (`0x4000`)
   - `libmlkit_google_ocr_pipeline.so` (`0x4000`)
   - `libonnxruntime.so` (`0x4000`)
   - `libonnxruntime4j_jni.so` (`0x4000`)
   - `libsherpa-onnx-jni.so` (`0x4000`)
   - `libsqliteJni.so` (`0x4000`)
4. **BuildConfig safety:** `LOG_DETAIL=false`, `ALLOW_UNSIGNED_MODELS=false`, `BATTERY_EXEMPTION_REQUEST=false`, `FGS_MEDIA_PROCESSING=false`.

---

## 3. Play Console Form Answers

### Data Safety
- **Does your app collect or share any of the required user data types?** **No**
- **Data collected:** None (`0` bytes collected)
- **Data shared with third parties:** None (`0` bytes shared)
- **Network access:** App declares **no `android.permission.INTERNET`**; all indexing, OCR, speech-to-text, embeddings, and search run 100% locally on device.
- **User accounts:** None required.

### Permissions Declarations
1. **All Files Access (`MANAGE_EXTERNAL_STORAGE`):**
   - **Core Use Case:** Device-wide local file search & management utility (permitted under Google Play's All Files Access policy for file search / file management apps).
   - **Why MediaStore / SAF is insufficient:** Users search across PDFs, Word/Excel/PowerPoint documents, Markdown/text notes, downloads, voice recordings, and photos stored across arbitrary folders on shared storage. Android MediaStore does not grant read access to non-media document formats (PDF, DOCX, XLSX, PPTX, TXT, MD, CSV, JSON) created by other apps, and SAF folder-by-folder grants break whole-device search across existing folders.
   - **Privacy Safeguard:** Trawl has **no `INTERNET` permission** and `allowBackup="false"`, guaranteeing indexed file contents cannot leave the device.
2. **Foreground Service (`FOREGROUND_SERVICE_DATA_SYNC`):**
   - **Use Case:** User-visible ongoing on-device indexing of local files with a persistent notification that shows file kind only (never file names) and opens Trawl on tap; respects battery, charging, thermal throttling, and user pause controls.
3. **Photo & Video Permissions (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`, `READ_MEDIA_VISUAL_USER_SELECTED`):**
   - Used to read local media metadata and thumbnails for offline search and in-app preview.

---

## 4. 30-Second Play Review Video Script (`MANAGE_EXTERNAL_STORAGE` + `dataSync` FGS)

Record a single uninterrupted ~30-second screen recording (no audio required, English UI):

1. **0:00–0:06 (Launch & Permission Rationale):** Open **Trawl** from the launcher. Show the onboarding explanation stating that Trawl searches documents, PDFs, photos, videos, and audio privately on-device with zero internet access, and tap **Allow file access**.
2. **0:06–0:11 (System Settings Grant):** On Android's **All files access** settings page, toggle **Allow access to manage all files** ON for Trawl and press Back to return to Trawl.
3. **0:11–0:18 (Local Indexing & Foreground Notification):** Show the indexing meter scanning local folders (Documents, Downloads, DCIM). Pull down the notification shade briefly to show the ongoing **Trawl** indexing notification (`dataSync`), then tap the notification to return directly to Trawl.
4. **0:18–0:26 (Cross-Folder Non-Media Document Search):** Type a keyword matching a non-media document (e.g. a `.pdf`, `.docx`, or `.csv` in `Documents/` or `Download/`) to demonstrate finding and previewing non-media files across storage that MediaStore cannot access.
5. **0:26–0:30 (Offline Privacy Confirmation):** Open **Settings** briefly to show local storage/folder controls and confirm no account or cloud sync exists.
