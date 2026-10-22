#!/bin/bash
# Builds everything a Trawl release needs and PRINTS the commands that publish it. It never talks to GitHub: it does not run `gh`,
# does not push, does not tag. Publishing is a separate, deliberate step: read the output, then paste the commands yourself.
#
#   scripts/release.sh <version>                 e.g. scripts/release.sh 1.0.0
#   scripts/release.sh <version> --print-only    build nothing; print the commands for what is already in dist/
#   scripts/release.sh <version> --no-models     build the app only (the models release already exists)
#   scripts/release.sh <version> --remote        no Gradle on this machine: build on the box named by TRAWL_BUILD_SSH=user@host
#                                                (scripts/remote-gradle.sh), then zip-align and sign BOTH APKs here with apksigner and the
#                                                Trawl key from ~/.gradle/gradle.properties (passwords pass only as env vars, never printed).
#                                                The key never leaves this machine. Everything after the build is the same as the local path.
#   scripts/release.sh <version> --remote --skip-build   re-sign the APKs a previous --remote run left in app/build/outputs
# Whichever path built them, an APK must carry the genuine Trawl certificate (SHA-256 pinned below, TRAWL_CERT_SHA256 overrides for
# tests) and no INTERNET permission, or the script stops.
#
# What it does, in order:
#   1. checks the version equals versionName in app/build.gradle.kts, the work tree is clean, and the publishing key is set up
#      (scripts/make-signing-key.sh; ~/.gradle/gradle.properties holds trawlKeystore and its passwords);
#   2. builds the two signed release APKs (it fails rather than debug-sign):
#        Trawl, 64-bit:     ./gradlew :app:assembleRelease -PtrawlPublish=true     -> trawl-<version>.apk
#        Trawl Go, 32-bit:  ./gradlew :app:assembleGoRelease -PtrawlPublish=true   -> trawl-go-<version>.apk (words only, no models)
#   3. builds the two model bundles with scripts/pack-bundle.sh: standard and lite (LITE=1). They are signed in an Azure Key Vault
#      over ssh, exactly as pack-bundle.sh does: set TRAWL_SIGN_SSH=user@host and TRAWL_VAULT=<vault name> (no defaults).
#      Needs bench-data/models/ and models/private/;
#   4. copies them to dist/ under the names the app and the site expect, with SHA256SUMS, and writes the release notes;
#   5. prints the `gh release create` commands.
#
# Two releases, two tags, on purpose:
#   v<version>   the app (trawl-<version>.apk and trawl-go-<version>.apk). One per app version.
#   models-v<N>  the model bundles (trawl-models.zip, trawl-models-lite.zip), N = the bundle version in
#                app/src/main/assets/models-known.json. The app downloads from
#                https://github.com/Crane-AI-Labs/trawl/releases/download/models-v1/ (MODELS_BASE_URL in app/build.gradle.kts),
#                so that URL must never change under an installed app: a new app release does NOT re-upload the models. Raise N
#                (and MODELS_BASE_URL with it) only with a release that ships better models.
# The sandbox rule still holds: this script touches no phone.
set -euo pipefail
cd "$(dirname "$0")/.."

REPO=Crane-AI-Labs/trawl
VERSION=${1:-}; shift || true
PRINT_ONLY=0; NO_MODELS=0; REMOTE=0; SKIP_BUILD=0
for a in "$@"; do case "$a" in --print-only) PRINT_ONLY=1;; --no-models) NO_MODELS=1;; --remote) REMOTE=1;; --skip-build) SKIP_BUILD=1;; *) echo "unknown option: $a"; exit 2;; esac; done
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "usage: scripts/release.sh <version, like 1.0.0> [--print-only|--no-models|--remote|--skip-build]"; exit 2; }
[ "$SKIP_BUILD" = 0 ] || [ "$REMOTE" = 1 ] || { echo "--skip-build goes with --remote (it re-signs the APKs a previous --remote run left in app/build/outputs)."; exit 2; }

# The genuine Trawl signing certificate (SHA-256). Public: it is printed in the release notes. Override only to test the failure path.
GENUINE_CERT=${TRAWL_CERT_SHA256:-3d056763b58093e91006f00b44d98d47bfe5f26140be6105598363e670708d80}
SDK_BT=$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/*/ 2>/dev/null | sort -V | tail -1 || true)

GRADLE_VERSION=$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' app/build.gradle.kts | head -1)
if [ "$NO_MODELS" = 0 ] && [ "$PRINT_ONLY" = 0 ]; then
  : "${TRAWL_SIGN_SSH:?set TRAWL_SIGN_SSH=user@host (the machine that signs the model bundles) or pass --no-models}"
  : "${TRAWL_VAULT:?set TRAWL_VAULT=<Azure Key Vault name> (the vault that holds the bundle-signing key) or pass --no-models}"
fi
[ "$VERSION" = "$GRADLE_VERSION" ] || { echo "versionName in app/build.gradle.kts is $GRADLE_VERSION, not $VERSION. Raise it (and versionCode) first."; exit 1; }
read -r STD LITE_V < <(python3 -c "import json;c=json.load(open('app/src/main/assets/models-known.json'))['classes'];print(c['standard']['version'],c['lite']['version'])")
[ "$STD" = "$LITE_V" ] || { echo "standard bundle is version $STD and lite is $LITE_V; they ship under one models tag, so keep them equal."; exit 1; }
MODELS_TAG="models-v$STD"
BASE_URL=$(sed -n 's/.*"https:\/\/github.com\/\([^"]*\)\/releases\/download\/\([^/"]*\)\/".*/\2/p' app/build.gradle.kts | head -1)
[ "$BASE_URL" = "$MODELS_TAG" ] || { echo "MODELS_BASE_URL in app/build.gradle.kts points at '${BASE_URL:-nothing}', but the bundles are version $STD, so tag $MODELS_TAG. Change one of them."; exit 1; }

APP_TAG="v$VERSION"
APP_DIR="dist/$APP_TAG"; MODELS_DIR="dist/$MODELS_TAG"
APK="$APP_DIR/trawl-$VERSION.apk"
GO_APK="$APP_DIR/trawl-go-$VERSION.apk"

if [ "$PRINT_ONLY" = 0 ]; then
  if [ -n "$(git status --porcelain --untracked-files=no)" ]; then echo "The work tree has uncommitted changes. A release is built from a commit."; git status --short --untracked-files=no | head; exit 1; fi
  grep -q '^trawlKeystore=' ~/.gradle/gradle.properties 2>/dev/null || { echo "No publishing key. Run scripts/make-signing-key.sh once (it writes ~/.gradle/gradle.properties)."; exit 1; }
  mkdir -p "$APP_DIR"
  rm -f "$APK" "$GO_APK" "$APP_DIR/SHA256SUMS" "$APP_DIR/certificate.txt"   # a stopped run must not leave the previous build's files looking current
  APKSIGNER=${SDK_BT:+${SDK_BT}apksigner}; [ -x "${APKSIGNER:-}" ] || APKSIGNER=""
  if [ "$REMOTE" = 0 ]; then
    echo "== signed release APKs (Trawl 64-bit, Trawl Go 32-bit)"
    ./gradlew :app:assembleRelease :app:assembleGoRelease -PtrawlPublish=true -q
    cp app/build/outputs/apk/release/app-release.apk "$APK"
    cp app/build/outputs/apk/go/release/app-go-release.apk "$GO_APK"
  else
    # No Gradle on this machine: the build box makes the (debug-signed) release APKs; they are zip-aligned and signed HERE with the
    # Trawl key, which never leaves this machine. The key passwords travel only as environment variables of the apksigner process.
    : "${TRAWL_BUILD_SSH:?--remote needs TRAWL_BUILD_SSH=user@host (the build box, see scripts/remote-gradle.sh)}"
    [ -n "$APKSIGNER" ] || { echo "--remote needs apksigner (Android build-tools under \$ANDROID_HOME/build-tools). Stopping."; exit 1; }
    ZIPALIGN=${SDK_BT}zipalign; [ -x "$ZIPALIGN" ] || { echo "zipalign not found next to apksigner. Stopping."; exit 1; }
    GP=${TRAWL_GRADLE_PROPERTIES:-$HOME/.gradle/gradle.properties}   # override only to test with a throwaway key
    prop() { sed -n "s/^$1=//p" "$GP" | head -1; }
    KS=$(prop trawlKeystore); KS_ALIAS=$(prop trawlKeyAlias)
    [ -f "$KS" ] && [ -n "$KS_ALIAS" ] && [ -n "$(prop trawlKeystorePassword)" ] && [ -n "$(prop trawlKeyPassword)" ] || { echo "~/.gradle/gradle.properties does not name a readable keystore with its alias and passwords. Stopping."; exit 1; }
    OUT_REL=app/build/outputs/apk/release/app-release.apk; OUT_GO=app/build/outputs/apk/go/release/app-go-release.apk
    if [ "$SKIP_BUILD" = 0 ]; then
      echo "== release APKs, built on $TRAWL_BUILD_SSH (unsigned for publishing: they come back debug-signed)"
      rm -f "$OUT_REL" "$OUT_GO"          # never sign a stale APK from an earlier build if this one fails
      scripts/remote-gradle.sh :app:assembleRelease :app:assembleGoRelease -q
    else
      echo "== --skip-build: re-signing the APKs already in app/build/outputs"
    fi
    [ -f "$OUT_REL" ] && [ -f "$OUT_GO" ] || { echo "The remote build left no APKs in app/build/outputs/apk. Stopping."; exit 1; }
    TMPD=$(mktemp -d); trap 'rm -rf "$TMPD"' EXIT
    sign_apk() { # <built apk> <final apk>: align (only if needed) then sign with the Trawl key
      local src=$1 dst=$2
      if ! "$ZIPALIGN" -c -p 4 "$src" >/dev/null 2>&1; then
        echo "   $(basename "$src") is not 4-byte/page aligned: aligning"; "$ZIPALIGN" -f -p 4 "$src" "$TMPD/aligned.apk"; src="$TMPD/aligned.apk"
      fi
      TRAWL_KS_PASS=$(prop trawlKeystorePassword) TRAWL_KEY_PASS=$(prop trawlKeyPassword) \
        "$APKSIGNER" sign --ks "$KS" --ks-key-alias "$KS_ALIAS" --ks-pass env:TRAWL_KS_PASS --key-pass env:TRAWL_KEY_PASS --out "$dst" "$src"
      rm -f "$dst.idsig"
      "$ZIPALIGN" -c -p 4 "$dst" || { echo "$dst is not aligned after signing. Stopping."; exit 1; }
      echo "   signed $(basename "$dst") (aligned: ok)"
    }
    echo "== sign on this machine (Trawl key)"
    sign_apk "$OUT_REL" "$APK"
    sign_apk "$OUT_GO" "$GO_APK"
  fi
  if [ -n "$APKSIGNER" ]; then
    for f in "$APK" "$GO_APK"; do
      "$APKSIGNER" verify --print-certs "$f" > "$APP_DIR/.verify.txt" || { echo "apksigner does not verify $f. Stopping."; exit 1; }
      grep -q "CN=Trawl" "$APP_DIR/.verify.txt" || { echo "$f is not signed with the Trawl key (is it debug-signed?). Stopping."; exit 1; }
      grep -q "certificate SHA-256 digest: $GENUINE_CERT" "$APP_DIR/.verify.txt" || { echo "$f is signed with a certificate that is NOT the genuine Trawl one ($GENUINE_CERT). Stopping."; exit 1; }
      ! grep -q "Signer #2" "$APP_DIR/.verify.txt" || { echo "$f has more than one signer. Stopping."; exit 1; }
    done
    "$APKSIGNER" verify --print-certs "$APK" | grep -E "certificate SHA-256|Signer #1 certificate DN" | tee "$APP_DIR/certificate.txt"
    rm -f "$APP_DIR/.verify.txt"
  else
    echo "apksigner not found: install Android build-tools to verify the signature before publishing."
  fi
  # Trawl's promise: no internet. The compiled manifest stores names as UTF-16; storage access must be found too, or the check itself is broken.
  python3 - "$APK" "$GO_APK" <<'EOF' || { echo "Stopping: an APK asks for the internet, or its manifest could not be read."; exit 1; }
import sys, zipfile
for apk in sys.argv[1:]:
    m = zipfile.ZipFile(apk).read("AndroidManifest.xml")
    has = lambda p: ("android.permission." + p).encode("utf-16-le") in m
    if not has("MANAGE_EXTERNAL_STORAGE"): sys.exit(f"{apk}: manifest not readable (no storage permission found)")
    if has("INTERNET"): sys.exit(f"{apk}: asks for android.permission.INTERNET")
    print(f"{apk}: no internet permission")
EOF
  if [ "$NO_MODELS" = 0 ]; then
    mkdir -p "$MODELS_DIR"
    echo "== standard bundle (signed in Key Vault)"; scripts/pack-bundle.sh;     cp bench-data/bundle/trawl-models.zip      "$MODELS_DIR/"
    echo "== lite bundle (signed in Key Vault)";     LITE=1 scripts/pack-bundle.sh; cp bench-data/bundle/trawl-models-lite.zip "$MODELS_DIR/"
  fi
fi

# With --no-models the bundles are not rebuilt: read their sizes from the existing dist/models-vN (this tree's, else the main checkout's,
# so a build from a linked worktree still finds the bundles the coordinator keeps in the main checkout's git-ignored dist/).
MAIN_ROOT=$(cd "$(git rev-parse --git-common-dir)/.." 2>/dev/null && pwd || pwd)
models_file() { if [ -f "$MODELS_DIR/$1" ]; then echo "$MODELS_DIR/$1"; else echo "$MAIN_ROOT/$MODELS_DIR/$1"; fi; }
size_mb() { [ -f "$1" ] && python3 -c "import os,sys;print(round(os.path.getsize(sys.argv[1])/1e6))" "$1" || echo "?"; }
CERT=$(sed -n 's/.*certificate SHA-256 digest: //p' "$APP_DIR/certificate.txt" 2>/dev/null | head -1 || true)
APK_MB=$(size_mb "$APK"); GO_MB=$(size_mb "$GO_APK"); STD_MB=$(size_mb "$(models_file trawl-models.zip)"); LITE_MB=$(size_mb "$(models_file trawl-models-lite.zip)")

if [ "$PRINT_ONLY" = 0 ]; then
  [ -f "$APK" ] && (cd "$APP_DIR" && shasum -a 256 "trawl-$VERSION.apk" "trawl-go-$VERSION.apk" > SHA256SUMS)
  [ "$NO_MODELS" = 0 ] && (cd "$MODELS_DIR" && shasum -a 256 trawl-models.zip trawl-models-lite.zip > SHA256SUMS)
  mkdir -p "$APP_DIR" "$MODELS_DIR"
  cat > "$APP_DIR/RELEASE_NOTES.md" <<EOF
# Trawl $VERSION

Find any photo, screenshot, document, video or voice note on your phone by describing it. It works fully offline: no internet
permission, no account, nothing leaves the phone.

## Install
1. Download \`trawl-$VERSION.apk\` below (about ${APK_MB} MB) and open it. Android will ask you to allow installs from your browser or
   file manager: that is normal for an app outside Google Play.
2. Open Trawl. On the first run, **Get the models** downloads the search models for your phone (standard, about ${STD_MB} MB, or lite
   for a 2 GB phone, about ${LITE_MB} MB). Or download them once from the [\`$MODELS_TAG\` release](https://github.com/$REPO/releases/tag/$MODELS_TAG)
   on another device and copy the zip to your phone's Downloads folder: Trawl finds it by itself.
3. Allow **All files access** when asked, so Trawl can read the files it searches. It never changes them.

Needs Android 11 or newer and a 64-bit phone (almost every phone sold since 2019).

### Trawl Go, for 32-bit Android Go phones
If Android says the app "isn't compatible with your phone", the phone is 32-bit: install \`trawl-go-$VERSION.apk\` (about ${GO_MB} MB)
instead. Trawl Go needs no models download. It finds files by their names, the words in documents and the words in photos and
screenshots; it does not search by meaning, and it does not listen to recordings.

## What's new
$(cat "docs/release-notes/$VERSION.md" 2>/dev/null || echo "TODO(release notes): write docs/release-notes/$VERSION.md (what changed, in plain words) before publishing.")

## Check the download
\`SHA256SUMS\` has the checksums of both APKs. The signing certificate fingerprint (SHA-256) of genuine Trawl builds is:
\`${CERT:-TODO: paste the fingerprint printed by scripts/make-signing-key.sh}\`
EOF
  cat > "$MODELS_DIR/RELEASE_NOTES.md" <<EOF
# Trawl search models ($MODELS_TAG)

The models Trawl searches with, as signed one-file bundles. **Most people do not need this page**: inside the app, **Get the models**
downloads the right bundle for the phone. Use these files to download on another device or a computer and copy them across.

| File | For | Size |
|---|---|---|
| \`trawl-models.zip\` | phones with 3 GB of memory or more (standard) | about ${STD_MB} MB |
| \`trawl-models-lite.zip\` | phones with 2 GB of memory (lite) | about ${LITE_MB} MB |

Copy the zip to the phone's Downloads folder, then open Trawl. Trawl checks the signature and every file's checksum before it uses a
bundle, and refuses one that does not verify. Bundle version $STD. Licences of the models: see THIRD_PARTY_NOTICES.md in the repository
(EmbeddingGemma 2 Apache-2.0, TinyCLIP MIT, Parakeet CC BY 4.0 by NVIDIA, Marqo nsfw-image-detection-384 Apache-2.0, Silero VAD MIT,
CED-mini Apache-2.0).

This release is never edited or replaced once published: installed apps download from this exact address.
EOF
fi

echo
echo "== built (nothing has been published)"
ls -l "$APK" "$GO_APK" "$MODELS_DIR"/*.zip 2>/dev/null || true
echo
echo "== publish: read the notes, finish the TODOs in them, then paste. Needs gh logged in to an account that can write to $REPO,"
echo "   and the repo pushed with this commit ($(git rev-parse --short HEAD)) on its main branch."
echo
if [ "$NO_MODELS" = 0 ]; then
  echo "# 1. models, FIRST and only once per $MODELS_TAG (the app's download URL points here; skip if the release already exists)"
  echo "gh release create $MODELS_TAG --repo $REPO --target main --latest=false \\"
  echo "  --title \"Trawl search models ($MODELS_TAG)\" --notes-file $MODELS_DIR/RELEASE_NOTES.md \\"
  echo "  $MODELS_DIR/trawl-models.zip $MODELS_DIR/trawl-models-lite.zip $MODELS_DIR/SHA256SUMS"
  echo
fi
echo "# 2. the app"
echo "gh release create $APP_TAG --repo $REPO --target main --latest \\"
echo "  --title \"Trawl $VERSION\" --notes-file $APP_DIR/RELEASE_NOTES.md \\"
echo "  $APK $GO_APK $APP_DIR/SHA256SUMS"
echo
echo "# 3. then point the landing page at the four files (the TODO comment in site/index.html lists them):"
echo "#    https://github.com/$REPO/releases/download/$APP_TAG/trawl-$VERSION.apk"
echo "#    https://github.com/$REPO/releases/download/$APP_TAG/trawl-go-$VERSION.apk"
echo "#    https://github.com/$REPO/releases/download/$MODELS_TAG/trawl-models.zip"
echo "#    https://github.com/$REPO/releases/download/$MODELS_TAG/trawl-models-lite.zip"
