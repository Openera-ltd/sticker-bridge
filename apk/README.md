# Sticker Bridge for Android

Send a TikTok sticker to @stickerbridge, and it lands in a WhatsApp pack automatically.

## Builds

| Build | What it's for |
| --- | --- |
| `live` | The real app. Talks only to `https://api.sticker.openera.tech/`. |
| `demo` | Works with no server: fake account, a new demo sticker every sync. For trying the app. Installs next to `live`. |

```
./gradlew assembleDemoRelease   # app/build/outputs/apk/demo/release/
./gradlew assembleLiveRelease   # app/build/outputs/apk/live/release/
./gradlew testDemoDebugUnitTest # all tests
```

Needs JDK 17+ and the Android SDK (platform 35). Release signing reads `keystore.properties`
(never committed):

```
storeFile=/path/to/sticker-bridge-release.jks
storePassword=...
keyAlias=stickerbridge
keyPassword=...
```

**Every update must be signed with the same key**, or phones refuse to install it.

## What's inside

- `data/` — the API client (locked to one domain), sticker conversion to WhatsApp's format
  (512×512 WebP, under 100 KB, optional white outline), pack building (3–30 per pack,
  still and animated kept apart), local storage, sync.
- `whatsapp/` — the provider WhatsApp reads packs from, and the "Add to WhatsApp" request.
  Pack versions go up whenever contents change, so WhatsApp refreshes them on its own.
- `sync/` — background sync with WorkManager (every 15 min to 12 h, optional Wi-Fi only).
- `update/` — in-app updates: download from the API, check SHA-256 and signing key, install.
- `ui/` — Jetpack Compose screens: welcome, connect, stickers, packs, settings.

Server contract: [`../backend/API.md`](../backend/API.md).
