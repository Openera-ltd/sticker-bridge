#!/usr/bin/env bash
# Publishes an APK as an in-app update.
# Usage: ADMIN_SECRET=... scripts/publish-apk.sh <versionCode> <versionName> <apk> ["notes"] [mandatory=0|1]
set -euo pipefail
code=$1; name=$2; apk=$3; notes=${4:-}; mandatory=${5:-0}
base=${API_BASE:-https://api.sticker.openera.tech}

# Refuse to publish an APK whose code Android would reject (this shipped in 1.0.5 and 1.0.6).
dexdump=${DEXDUMP:-$(ls -d "${ANDROID_HOME:-/opt/android-sdk}"/build-tools/*/dexdump 2>/dev/null | tail -1)}
if [ -z "$dexdump" ]; then echo "dexdump not found (set DEXDUMP)" >&2; exit 1; fi
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
unzip -q "$apk" 'classes*.dex' -d "$tmp"
for d in "$tmp"/classes*.dex; do
  out=$("$dexdump" -f "$d" 2>&1 || true)
  if grep -q "Failure to verify" <<<"$out"; then
    echo "$(basename "$d") is not valid: Android would refuse to open this app. Not publishing." >&2; exit 1
  fi
done
q="name=$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$name")&notes=$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$notes")&mandatory=$mandatory"
curl -fsS -X PUT "$base/admin/releases/$code?$q" \
  -H "authorization: Bearer $ADMIN_SECRET" \
  -H "content-type: application/vnd.android.package-archive" \
  --data-binary "@$apk"
echo
