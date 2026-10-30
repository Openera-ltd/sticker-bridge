# Sticker Bridge API (v1)

The Android app talks to **one** address only: `https://api.sticker.openera.tech/`.
It refuses any other host, plain HTTP and redirects. All paths below are relative to that base.
JSON everywhere; unknown fields are ignored by the app, so fields can be added freely.

## Connecting a TikTok account

### `POST v1/link/start`
Body: `{"installId": "<uuid>"}`
Returns a one-time code the user sends to @stickerbridge on TikTok.
```json
{"code": "SB-4821", "pollToken": "random-secret", "expiresAt": 1793366400000}
```
- Codes: random, single use, expire after 10 minutes (`expiresAt` is ms since epoch).
- `pollToken` stops others from polling someone else's code.

### `GET v1/link/status?code=SB-4821`
Header: `X-Poll-Token: <pollToken>`
```json
{"status": "pending"}
{"status": "linked", "token": "<bearer token>", "account": {"id": "<tiktok internal id>", "username": "mia.draws"}}
{"status": "expired"}
```
The app polls every 2 seconds. Link by TikTok's **internal account id**, not the username.

### `DELETE v1/link` (Bearer)
Disconnects this phone. Stickers on the server stay until they expire.

### `DELETE v1/me` (Bearer)
Deletes the account's stickers and links on the server.

## Stickers

### `GET v1/stickers[?since=<cursor>]` (Bearer)
Stickers received after the cursor, oldest first.
```json
{"items": [{"id": "abc123", "file": "v1/stickers/abc123/file", "animated": false, "createdAt": 1793366400000, "replaces": null}],
 "cursor": "opaque-string"}
```
- The app keeps asking with the new cursor until a page comes back empty (max 10 pages per sync).
- `id`: letters, digits, `-`, `_` only (max 64).
- `replaces`: id of an older copy of this sticker (for example a still image remade as animated).
  The app swaps it in place, in every pack it was in, and deletes the old one.
- `401` means the link is gone: the app signs out and asks the user to connect again.

### `GET <file>` (Bearer)
The sticker bytes.
- Still stickers: PNG, JPEG or WebP, any size. The app converts them to 512×512 WebP under 100 KB.
- Animated stickers: the **server** must convert them to animated WebP, exactly 512×512, under 500 KB,
  10 seconds or less. The app skips animated files that break these rules.

## App

### `GET v1/config`
```json
{"minVersionCode": 0, "notice": null, "botUsername": "stickerbridge"}
```
`notice` shows a short message at the top of the app (e.g. maintenance).

### `GET v1/app/latest`
The newest app release, or `404` if none.
```json
{"versionCode": 2, "versionName": "1.1.0", "apk": "v1/app/apk/2",
 "sha256": "<hex of the APK>", "size": 2148800, "notes": "Faster sync.", "mandatory": false}
```
The app shows an update card when `versionCode` is higher than its own, downloads `apk`
from this same domain, checks `sha256` and that the APK is signed with the same key, then asks Android to install it.

### `GET v1/app/apk/<versionCode>`
The APK file (`application/vnd.android.package-archive`).

### `GET v1/donation`
Where donations go, signed by the owner, or `404` if not set.
```json
{"payload": "{\"paypal\":\"https://paypal.me/cyriqfx\",\"momo\":\"+237672612437\"}", "signature": "<base64 DER ECDSA P-256>"}
```
The app checks `signature` over the exact `payload` text with the public key built into the app
(`DONATION_KEY`), and hides the section if it fails, so changing the database alone can't redirect donations.
Release builds also hide it if the app isn't signed with the Sticker Bridge release key.
To change it: `node backend/scripts/sign-donation.mjs <private.pem> '<payload json>'` (PUTs to `admin/donation`).

## Admin (Bearer `ADMIN_SECRET`)
- `PUT admin/releases/<n>`, `PUT admin/config`: publish an update, change config.
- `PUT admin/donation`: store a signed donation payload. The app checks the signature, not the server.
- `POST admin/reconvert` `{"limit":2,"after":0}`: re-checks stickers stored as still images; animated
  originals are converted again and stored as new stickers with `replaces` set. Returns `{done, next, left}`.
- `GET v1/tmp/<id>`: short-lived files used during conversion; deleted right after.

## Publishing an update
1. Raise `versionCode` and `versionName` in `apk/app/build.gradle.kts`.
2. Build: `./gradlew assembleLiveRelease` (signed with the release key).
3. Upload the APK so `v1/app/apk/<versionCode>` serves it.
4. Point `v1/app/latest` at it with the APK's SHA-256 (`sha256sum app-live-release.apk`).
