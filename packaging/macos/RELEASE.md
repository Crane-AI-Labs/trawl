# macOS Release, Notarization, and Homebrew Cask Guide

## 1. Build the Signed DMG

On Apple Silicon (`arm64`, macOS 13+):

```bash
cd desktop
../gradlew -Dorg.gradle.jvmargs=-Xmx2g -Dkotlin.daemon.jvmargs=-Xmx2g \
  packageDmg checkLauncherHeap checkMacPrivacyStrings checkPackagedRuntime \
  checkJvmClassfileVersion checkNoTelemetryInNatives --no-daemon
```

The DMG lands at `desktop/build/compose/binaries/main/dmg/Trawl-1.1.0.dmg` (published as `Trawl-1.1.0-arm64.dmg` on tag `desktop-1.1.0`).
The build automatically runs `desktop/packaging/reseal-dmg.sh` to fix jpackage's post-sign `.package` seal for ad-hoc builds.

## 2. Developer ID Signing & Apple Notarization

When shipping publicly with an Apple Developer ID Application certificate (`$MAC_SIGN_IDENTITY`):

```bash
APP="desktop/build/compose/binaries/main/app/Trawl.app"
DMG="desktop/build/compose/binaries/main/dmg/Trawl-1.1.0.dmg"

# 1. Sign nested native binaries & dylibs with hardened runtime + timestamp
codesign --force --options runtime --timestamp \
  --entitlements desktop/packaging/trawl.entitlements \
  --sign "$MAC_SIGN_IDENTITY" --deep "$APP"

codesign --verify --deep --strict --verbose=2 "$APP"

# 2. Package DMG and sign the DMG itself
codesign --force --timestamp --sign "$MAC_SIGN_IDENTITY" "$DMG"

# 3. Submit to Apple Notary Service and staple ticket
xcrun notarytool submit "$DMG" --keychain-profile "trawl-notary" --wait
xcrun stapler staple "$DMG"
xcrun stapler validate "$DMG"
spctl --assess --type open --context context:primary-signature -v "$DMG"
```

## 3. Update Homebrew Cask SHA-256

```bash
shasum -a 256 "$DMG"
```

Replace `REPLACE_WITH_RELEASE_DMG_SHA256` in `packaging/homebrew/trawl.rb` and bump `version` to match `AppInfo.VERSION` and `desktop/build.gradle.kts` `packageVersion`.

## 4. Verification Checklist

- Zero absolute developer paths in shipped dylibs (`otool -D` returns `@rpath/libtrawl_mac.dylib` and `@rpath/libtrawl_vec_neon.dylib`).
- `python3 desktop/scripts/net-gate.py` confirms zero outbound network connections (D43).
- `python3 scripts/garden-lint.py` passes with 0 violations (`G1`–`G29`).
