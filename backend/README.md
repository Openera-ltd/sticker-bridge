# Sticker Bridge backend

The API, database and files run on Cloudflare (account `Admin@openera.tech`).
The @stickerbridge bot runs on Render (free web service).

| Part | Cloudflare product | Name |
| --- | --- | --- |
| API the app talks to | Worker + custom domain | `sticker-bridge-api` at `https://api.sticker.openera.tech` |
| Database | D1 | `sticker-bridge` |
| Sticker and APK files | R2 | `stiker-bridge` |
| @stickerbridge bot | **Render** web service (Docker, free) | `sticker-bridge-bot`, reads the inbox every 60 s |
| Keep the bot awake | Cron on the API worker | pings the bot's `/healthz` every 10 min |
| Clean-up (stickers older than 30 days) | Cron on the API worker | daily 03:17 UTC |

API contract: [`API.md`](API.md). First-install download: `https://api.sticker.openera.tech/download`.

## Deploy

```
npm install
export CLOUDFLARE_API_TOKEN=...  CLOUDFLARE_ACCOUNT_ID=f276304fe252aab8abb55d37c3e27092
npm run migrate          # database tables
npm run deploy           # API
```

The bot deploys on Render from `backend/bot` (Dockerfile) whenever the branch is pushed.

Secrets (already set; change with `npx wrangler secret put NAME`):

| Worker | Secret | What |
| --- | --- | --- |
| API | `BOT_SECRET` | The bot's key for `/internal/inbox` |
| API | `ADMIN_SECRET` | For publishing updates and config |
| bot (Render env) | `BOT_SECRET` | Same value as the API's |
| bot (Render env) | `TIKTOK_COOKIES` | **Not set yet** — the @stickerbridge login (see below) |

## Connecting the bot to @stickerbridge

1. On a computer, log into tiktok.com as @stickerbridge.
2. Export the tiktok.com cookies (e.g. with a "cookie export" browser extension, as JSON).
3. In Render → sticker-bridge-bot → Environment, set `TIKTOK_COOKIES` to them.
   After the first good run the bot saves its login on Cloudflare (R2 `bot/state.json`),
   so restarts keep working.
4. Test: `curl -X POST https://sticker-bridge-bot.onrender.com/run -H "authorization: Bearer $BOT_SECRET"`.
   A good run reports `"loggedIn": true` and the chats it read. `GET /healthz` shows the last run.

TikTok's inbox page is undocumented; if the bot reports `loggedIn: false` while the cookies are valid,
adjust the selectors in `bot/src/inbox.ts`. Replies in TikTok are off by default (`SEND_REPLIES=true` to turn on).

Render free: 512 MB, the bot runs a slimmed-down Chromium and restarts it every 30 runs.
Settings (Render env): `POLL_SECONDS` (default 60), `MAX_CHATS` (10), `SEND_REPLIES` (false).

## Publishing an app update

1. Raise `versionCode` / `versionName` in `apk/app/build.gradle.kts`, build `assembleLiveRelease`.
2. `ADMIN_SECRET=... scripts/publish-apk.sh <versionCode> <versionName> <apk> "What's new"`

Phones see it within a day (or right away with "Check for updates").

## Tests

- `npm test` — the API, against a local copy of Workers, D1 and R2.
- `cd bot && npm test` — the bot, including runs in a real Chromium against a fake TikTok inbox.
