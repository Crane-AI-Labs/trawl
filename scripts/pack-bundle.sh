#!/bin/bash
# Builds the one-file models bundle trawl-models.zip: the embedding model plus the quick-look, speech and private-preview packs, which is what a
# new phone needs and what people pass around over WhatsApp or Xender (engine/ModelBundle.kt reads it from Downloads). Stored,
# not deflated: the models are already compressed. The app installs the model and all three packs from it without a question; the
# separate files (the .litertlm, findr-quicklook-models.zip, findr-speech-models.zip, findr-private-models.zip) still work too.
#   scripts/pack-bundle.sh [--push]          (MODEL=<.litertlm> OUT=bench-data/bundle)
#   LITE=1 scripts/pack-bundle.sh            trawl-models-lite.zip for 2 GB phones: the text-only 270m model, quick look and
#                                            private previews, no speech (the lite profile keeps speech off: the research notes)
#   ANDROID_SERIAL=<serial> scripts/phone-lock.sh run <who> scripts/pack-bundle.sh --push
# The bundle is SIGNED (engine/SignedBundle.kt): trawl-bundle.json (id, class, version from app/src/main/assets/models-known.json,
# and the name, size and SHA-256 of every file) and trawl-bundle.sig (ECDSA P-256 over the manifest, signed by `az keyvault key sign`
# in an Azure Key Vault, so the private key never leaves the vault) are the FIRST two zip entries, which lets the phone verify
# and refuse in one streaming pass. Signing needs two settings and has NO defaults:
#   TRAWL_SIGN_SSH=user@host   a machine you can ssh to (non-interactively) that is logged in to `az` with sign rights on the vault
#   TRAWL_VAULT=<vault name>   the Azure Key Vault holding the signing key
# SIGN_KEY=<key name> picks the key (default trawl-models-signing-1; -2 is the spare); its public half must be in
# engine/SignedBundle.kt (TrustedKeys). NO_SIGN=1 builds an unsigned bundle (only debug or -PallowUnsignedModels builds of the app accept it).
# Layout (the same as the separate downloads, so every file is checked by the same manifests):
#   embeddinggemma-2-text-vision-440m.litertlm
#   findr-quicklook-models/<engine/QuickLookPack.kt files>
#   findr-speech-models/<engine/AsrModelPack.kt files>
#   findr-private-models/<engine/PrivatePack.kt files>   (SRC_PRIVATE=<dir with nsfw-384.onnx, LICENSE.txt, SOURCE.txt>, default models/private)
set -euo pipefail
cd "$(dirname "$0")/.."
LITE=${LITE:-0}
# VARIANT is the lean download's split (internal/research/lean-download.md). The CORE bundle is the lite file as it is (LITE=1: the
# 270m text model, quick look, private previews: 304 MB), which a standard phone installs too. The two later pieces:
#   VARIANT=vision   trawl-models-vision.zip: the 440m model alone, class standard. It upgrades an installed core whatever its version.
#   VARIANT=speech   trawl-models-speech.zip: the speech pack alone, role addon, class any: installs on any phone, model or none.
# Nothing here changes the full and lite bundles above.
VARIANT=${VARIANT:-}
ROLE=""
case "$VARIANT" in
  vision) MODEL=${MODEL:-bench-data/models/embeddinggemma-2-text-vision-440m.litertlm}; BUNDLE=trawl-models-vision; PACKS="" ;;
  speech) MODEL=""; BUNDLE=trawl-models-speech; PACKS="findr-speech-models"; ROLE=addon ;;
  "") if [ "$LITE" = 1 ]; then
    MODEL=${MODEL:-bench-data/models/embeddinggemma-2-text-270m.litertlm}; BUNDLE=trawl-models-lite; PACKS="findr-quicklook-models findr-private-models"
  else
    MODEL=${MODEL:-bench-data/models/embeddinggemma-2-text-vision-440m.litertlm}; BUNDLE=trawl-models; PACKS="findr-quicklook-models findr-speech-models findr-private-models"
  fi ;;
  *) echo "VARIANT must be vision or speech (or empty)"; exit 2 ;;
esac
OUT=${OUT:-bench-data/bundle}
PUSH=0
for a in "$@"; do case "$a" in --push) PUSH=1;; *) echo "unknown: $a"; exit 2;; esac; done
NAME=""
if [ -n "$MODEL" ]; then
  [ -f "$MODEL" ] || { echo "no model at $MODEL"; exit 1; }
  NAME=$(basename "$MODEL")
  # The app only installs a model it knows by name (Embedder.kt); a bundle with any other name would be skipped silently.
  grep -q "\"$NAME\"" app/src/main/java/com/craneai/findr/engine/Embedder.kt || { echo "Embedder.kt does not list $NAME"; exit 1; }
fi
# The packs are built and checked against the app's own manifests by their scripts (size and SHA-256 per file).
case " $PACKS " in *" findr-speech-models "*) env -u OUT scripts/pack-asr-models.sh >/dev/null ;; esac
case " $PACKS " in *" findr-quicklook-models "*) env -u OUT scripts/pack-quicklook-models.sh >/dev/null ;; esac
case " $PACKS " in *" findr-private-models "*) env -u OUT SRC=${SRC_PRIVATE:-models/private} scripts/pack-private-models.sh >/dev/null ;; esac
# (The standard bundle used to build the speech pack even for LITE=1 and never packed it; only what a bundle carries is built now.)
S="$OUT/stage"
rm -rf "$S" "$OUT/$BUNDLE.zip"; mkdir -p "$S"
clone() { cp -Rc "$1" "$2" 2>/dev/null || cp -R "$1" "$2"; }
[ -z "$NAME" ] || clone "$MODEL" "$S/$NAME"
case " $PACKS " in *" findr-speech-models "*) clone bench-data/asr-pack/findr-speech-models "$S/findr-speech-models" ;; esac
case " $PACKS " in *" findr-quicklook-models "*) clone bench-data/quicklook-pack/findr-quicklook-models "$S/findr-quicklook-models" ;; esac
case " $PACKS " in *" findr-private-models "*) clone bench-data/private-pack/findr-private-models "$S/findr-private-models" ;; esac
if [ "${NO_SIGN:-0}" != 1 ]; then
  CLASS=standard; [ "$LITE" = 1 ] && CLASS=lite
  VERSION=$(python3 -c "import json;print(json.load(open('app/src/main/assets/models-known.json'))['classes']['$CLASS']['version'])")
  if [ "$ROLE" = addon ]; then
    CLASS=any
    VERSION=$(python3 -c "import json;print(json.load(open('app/src/main/assets/models-known.json'))['addons']['speech']['version'])")
  fi
  KEY=${SIGN_KEY:-trawl-models-signing-1}
  : "${TRAWL_SIGN_SSH:?set TRAWL_SIGN_SSH=user@host: the machine that signs (az logged in), or use NO_SIGN=1 for an unsigned test bundle}"
  : "${TRAWL_VAULT:?set TRAWL_VAULT=<Azure Key Vault name> that holds the signing key, or use NO_SIGN=1 for an unsigned test bundle}"
  (cd "$S" && python3 - "$BUNDLE" "$CLASS" "$VERSION" "$KEY" "$ROLE" <<'PY'
import hashlib, json, os, sys
bundle, cls, version, key, role = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4], sys.argv[5]
files = []
for root, _, names in os.walk('.'):
    for n in names:
        p = os.path.relpath(os.path.join(root, n), '.')
        h = hashlib.sha256()
        with open(p, 'rb') as f:
            for chunk in iter(lambda: f.read(1 << 20), b''):
                h.update(chunk)
        files.append({'path': p.replace(os.sep, '/'), 'size': os.path.getsize(p), 'sha256': h.hexdigest()})
files.sort(key=lambda f: f['path'])
manifest = json.dumps(dict({'format': 1, 'bundle': bundle, 'class': cls, 'version': version, 'key': key}, **({'role': role} if role else {}), files=files), indent=1).encode()
open('trawl-bundle.json', 'wb').write(manifest)
open('.digest', 'w').write(__import__('base64').b64encode(hashlib.sha256(manifest).digest()).decode())
PY
  )
  DIGEST=$(cat "$S/.digest"); rm "$S/.digest"
  # Key Vault signs the digest (ES256) and returns the raw r||s as base64; the private key stays in the vault.
  SIG=$(ssh -o BatchMode=yes "$TRAWL_SIGN_SSH" "az keyvault key sign --vault-name $TRAWL_VAULT --name $KEY --algorithm ES256 --digest '$DIGEST' --query signature -o tsv")
  [ -n "$SIG" ] || { echo "signing failed"; exit 1; }
  printf '%s\n' "$SIG" > "$S/trawl-bundle.sig"
  # Prove the signature before shipping: against the public key the app embeds (engine/SignedBundle.kt TrustedKeys).
  python3 - "$S" "$KEY" <<'PY'
import base64, re, sys
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature
d, key = sys.argv[1:3]
src = open('app/src/main/java/com/craneai/findr/engine/SignedBundle.kt').read()
spki = re.search(r'KEY_%s = "([^"]+)"' % key[-1], src).group(1)
pub = serialization.load_der_public_key(base64.b64decode(spki))
raw = base64.b64decode(open(d + '/trawl-bundle.sig').read().strip())
assert len(raw) == 64, 'unexpected signature length %d' % len(raw)
pub.verify(encode_dss_signature(int.from_bytes(raw[:32], 'big'), int.from_bytes(raw[32:], 'big')), open(d + '/trawl-bundle.json', 'rb').read(), ec.ECDSA(hashes.SHA256()))
print('signature verifies against the app\'s embedded', key)
PY
  # Manifest and signature first: the phone reads them before it reads a byte of the models.
  (cd "$S" && zip -q -0 -X "../$BUNDLE.zip" trawl-bundle.json trawl-bundle.sig && zip -q -0 -r -X "../$BUNDLE.zip" ${NAME:+"$NAME"} $PACKS)
else
  (cd "$S" && zip -q -0 -r "../$BUNDLE.zip" ${NAME:+"$NAME"} $PACKS)
fi
rm -rf "$S"
unzip -tq "$OUT/$BUNDLE.zip"
unzip -l "$OUT/$BUNDLE.zip" | awk 'NF==4 && $1>1000000 {print $1, $4}'
ls -l "$OUT/$BUNDLE.zip"
if [ "$PUSH" = 1 ]; then
  : "${ANDROID_SERIAL:?name the phone: ANDROID_SERIAL=<serial>}"
  adb -s "$ANDROID_SERIAL" push "$OUT/$BUNDLE.zip" /sdcard/Download/ | tail -1
fi
