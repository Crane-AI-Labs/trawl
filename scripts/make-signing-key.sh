#!/bin/bash
# Creates Trawl's publishing key on THIS machine, and points the build at it. Run it once, as the person who publishes Trawl:
#   scripts/make-signing-key.sh
# The key and its passwords never enter the repo. Back up ~/keys/trawl-release.jks and the passwords in two places
# (your password manager, plus an offline copy): if the key is lost, people who sideloaded Trawl can never get an update.
set -euo pipefail
KEYDIR="$HOME/keys"; KEY="$KEYDIR/trawl-release.jks"; ALIAS=trawl; PROPS="$HOME/.gradle/gradle.properties"
[ -e "$KEY" ] && { echo "A key already exists at $KEY. Not overwriting it."; exit 1; }
mkdir -p "$KEYDIR" "$HOME/.gradle"; chmod 700 "$KEYDIR"
read -r -s -p "Choose a password for the key store (keep it in your password manager): " P1; echo
read -r -s -p "Type it again: " P2; echo
[ "$P1" = "$P2" ] || { echo "The passwords differ. Nothing was created."; exit 1; }
[ ${#P1} -ge 12 ] || { echo "Use at least 12 characters. Nothing was created."; exit 1; }
# One password for the store and the key (PKCS12 key stores use the same one).
keytool -genkeypair -v -keystore "$KEY" -storetype PKCS12 -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$P1" -keypass "$P1" -dname "CN=Trawl, O=Crane AI Labs, C=UG"
chmod 600 "$KEY"
touch "$PROPS"; chmod 600 "$PROPS"
grep -v -E '^trawl(Keystore|KeystorePassword|KeyAlias|KeyPassword)=' "$PROPS" > "$PROPS.tmp" || true
{ cat "$PROPS.tmp"; echo "trawlKeystore=$KEY"; echo "trawlKeystorePassword=$P1"; echo "trawlKeyAlias=$ALIAS"; echo "trawlKeyPassword=$P1"; } > "$PROPS"
rm -f "$PROPS.tmp"
echo
echo "Created $KEY and added its four lines to $PROPS (readable only by you)."
echo "Certificate fingerprint (share this one freely; it identifies genuine Trawl builds):"
keytool -list -v -keystore "$KEY" -storepass "$P1" -alias "$ALIAS" | grep -E "SHA256:" | head -1
echo
echo "Now back up $KEY and the password. To build a release:  ./gradlew :app:assembleRelease -PtrawlPublish=true"
