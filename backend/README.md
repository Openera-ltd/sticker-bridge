# Sticker Bridge backend (Cloudflare)

Everything runs on Cloudflare, account `Admin@openera.tech`.

| Part | Cloudflare product | Name |
| --- | --- | --- |
| API the app talks to | Worker + custom domain | `sticker-bridge-api` at `https://api.sticker.openera.tech` |
| Database | D1 | `sticker-bridge` |
| Sticker and APK files, bot login | R2 | `stiker-bridge` |
| @stickerbridge bot | Worker + Browser Rendering | `sticker-bridge-bot`, every 2 minutes |
| Clean-up (stickers older than 30 days) | Cron on the API worker | daily 03:17 UTC |

API contract: [`API.md`](API.md). First-install download: `https://api.sticker.openera.tech/download`.

## Deploy

```
export CLOUDFLARE_API_TOKEN=...  CLOUDFLARE_ACCOUNT_ID=f276304fe252aab8abb55d37c3e27092
npm install && npm run migrate && npm run deploy      # API
cd bot && npm install && npx wrangler deploy          # bot
```

Secrets (set with `npx wrangler secret put NAME`):

| Worker | Secret | What |
| --- | --- | --- |
| API | `BOT_SECRET` | The bot's key for `/internal/*` |
| API | `ADMIN_SECRET` | Publishing app updates and config |
| bot | `BOT_SECRET` | Same value as the API's |
| bot | `TIKTOK_COOKIES` | Optional: a first-time @stickerbridge login (cookies.txt, JSON or header format) |

## How the bot works

1. Opens tiktok.com/messages in Cloudflare's hosted browser, logged in as @stickerbridge.
2. Accepts any **Message requests** (people who don't follow @stickerbridge land there).
3. Reads the inbox's **message data** (`im-api.tiktok.com`). TikTok's website shows stickers as
   "This message type isn't supported", but the data behind the page holds each sticker's image link.
   `bot/src/imdata.ts` pulls out stickers and `SB-XXXXXX` codes with the sender's TikTok id.
4. Sends them to the API, which stores the sticker (or links the account). Each message is handled once.
5. Saves TikTok's refreshed login to R2 (`bot/state.json`), so the login keeps working.

The bot's login lives in R2. To replace it, PUT `/internal/bot-state` with `{"cookies":[...]}`
(bot key), or set `TIKTOK_COOKIES` and delete `bot/state.json`.

Check it: `GET https://sticker-bridge-bot.openera.workers.dev/healthz` shows the last run.
`POST /run` runs a pass now; `POST /debug?wait=12&open=0` shows what the page and its data look like
(structure only). Both need `authorization: Bearer <BOT_SECRET>`.

If TikTok changes its page: the selectors are in `bot/src/inbox.ts`; message-data parsing in `bot/src/imdata.ts`.

**Plan:** Browser Rendering on Workers Free is 10 minutes a day (about 30 checks). Workers Paid ($5/month)
includes 10 browser-hours a month, then $0.09/hour; one check takes about 20 s.

TikTok's DM stickers are animated WebP in sizes WhatsApp won't take as animated stickers,
so the app turns them into still stickers (first frame).

## Publishing an app update

1. Raise `versionCode` / `versionName` in `apk/app/build.gradle.kts`, build `assembleLiveRelease`.
2. `ADMIN_SECRET=... scripts/publish-apk.sh <versionCode> <versionName> <apk> "What's new"`

Phones see it within a day (or right away with "Check for updates").

## Tests

- `npm test` — the API, against a local copy of Workers, D1 and R2.
- `cd bot && npm test` — the bot: message-data parsing, and full passes in a real Chromium
  against a fake TikTok inbox.
