#!/usr/bin/env bash
# Publishes an APK as an in-app update.
# Usage: ADMIN_SECRET=... scripts/publish-apk.sh <versionCode> <versionName> <apk> ["notes"] [mandatory=0|1]
set -euo pipefail
code=$1; name=$2; apk=$3; notes=${4:-}; mandatory=${5:-0}
base=${API_BASE:-https://api.sticker.openera.tech}
q="name=$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$name")&notes=$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$notes")&mandatory=$mandatory"
curl -fsS -X PUT "$base/admin/releases/$code?$q" \
  -H "authorization: Bearer $ADMIN_SECRET" \
  -H "content-type: application/vnd.android.package-archive" \
  --data-binary "@$apk"
echo
