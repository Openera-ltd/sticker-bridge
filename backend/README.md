# Sticker Bridge backend (Cloudflare)

Everything runs on Cloudflare, account `Admin@openera.tech`.

| Part | Cloudflare product | Name |
| --- | --- | --- |
| API the app talks to | Worker + custom domain | `sticker-bridge-api` at `https://api.sticker.openera.tech` |
| Database | D1 | `sticker-bridge` |
| Sticker and APK files | R2 | `stiker-bridge` |
| @stickerbridge bot | Worker + Browser Rendering | `sticker-bridge-bot` (every 30 min) |
| Clean-up (stickers older than 30 days) | Cron on the API worker | daily 03:17 UTC |

API contract: [`API.md`](API.md). First-install download: `https://api.sticker.openera.tech/download`.

## Deploy

```
npm install
export CLOUDFLARE_API_TOKEN=...  CLOUDFLARE_ACCOUNT_ID=f276304fe252aab8abb55d37c3e27092
npm run migrate          # database tables
npm run deploy           # API
cd bot && npm install && npx wrangler deploy
```

Secrets (already set; change with `npx wrangler secret put NAME`):

| Worker | Secret | What |
| --- | --- | --- |
| API | `BOT_SECRET` | The bot's key for `/internal/inbox` |
| API | `ADMIN_SECRET` | For publishing updates and config |
| bot | `BOT_SECRET` | Same value as the API's |
| bot | `TIKTOK_COOKIES` | **Not set yet** — the @stickerbridge login (see below) |

## Connecting the bot to @stickerbridge

1. On a computer, log into tiktok.com as @stickerbridge.
2. Export the tiktok.com cookies (e.g. with a "cookie export" browser extension, as JSON).
3. `cd bot && npx wrangler secret put TIKTOK_COOKIES` and paste them.
4. Test: `curl -X POST https://sticker-bridge-bot.openera.workers.dev/run -H "authorization: Bearer $BOT_SECRET"`.
   A good run reports `"loggedIn": true` and the chats it read.

TikTok's inbox page is undocumented; if the bot reports `loggedIn: false` while the cookies are valid,
adjust the selectors in `bot/src/index.ts` → `readInbox()`. Replies in TikTok are off by default
(`SEND_REPLIES` in `bot/wrangler.jsonc`).

Free-plan limit: 10 browser-minutes a day. One run takes about 20 s, so every 30 minutes fits.
For sticker delivery within a minute or two, the Workers Paid plan ($5/month) is needed.

## Publishing an app update

1. Raise `versionCode` / `versionName` in `apk/app/build.gradle.kts`, build `assembleLiveRelease`.
2. `ADMIN_SECRET=... scripts/publish-apk.sh <versionCode> <versionName> <apk> "What's new"`

Phones see it within a day (or right away with "Check for updates").

## Tests

`npm test` — API and bot logic, run against a local copy of Workers, D1 and R2.
