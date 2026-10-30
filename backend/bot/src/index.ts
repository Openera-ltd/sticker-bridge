/**
 * The @stickerbridge bot. On a schedule, it opens TikTok's web inbox in Cloudflare's
 * hosted browser (logged in with the bot account's cookies), reads recent chats,
 * and passes codes and stickers to the API. Optionally it replies in the chat.
 *
 * TikTok's page layout is undocumented and changes. Everything that depends on it
 * lives in readInbox() below; adjust there if the bot stops finding messages.
 */
import puppeteer, { type Page } from "@cloudflare/puppeteer";
import { parseCookies, toInbox, type SeenChat } from "./messages";

export interface Env {
  BROWSER: Fetcher;
  /** The API worker (service binding), so bot traffic never leaves Cloudflare. */
  API: Fetcher;
  BOT_SECRET: string;
  /** @stickerbridge login cookies. */
  TIKTOK_COOKIES: string;
  MAX_CHATS: string;
  SEND_REPLIES: string;
}

interface RunReport {
  ok: boolean;
  loggedIn: boolean;
  chats: number;
  sent: number;
  results: Record<string, number>;
  error?: string;
  ms: number;
}

async function readInbox(page: Page, maxChats: number): Promise<SeenChat[]> {
  await page.goto("https://www.tiktok.com/messages?lang=en", { waitUntil: "networkidle2", timeout: 30_000 });
  // Conversation rows: TikTok marks them with data-e2e attributes or data-index.
  const rowSelector = '[data-e2e*="chat-list-item"], [data-e2e*="conversation"], [class*="ConversationListItem"], div[data-index]';
  await page.waitForSelector(rowSelector, { timeout: 15_000 });
  const count = Math.min(maxChats, await page.$$eval(rowSelector, (els) => els.length));
  const chats: SeenChat[] = [];

  for (let i = 0; i < count; i++) {
    const rows = await page.$$(rowSelector);
    if (!rows[i]) break;
    await rows[i].click();
    await new Promise((r) => setTimeout(r, 1800));
    const chat = await page.evaluate(() => {
      const main = (document.querySelector('[data-e2e*="chat-main"], [class*="ChatMain"], [class*="ChatBox"], main') ?? document.body) as HTMLElement;
      // Who we're talking to: the profile link in the chat header.
      const link = main.querySelector('a[href*="/@"]') as HTMLAnchorElement | null;
      const username = link ? (link.getAttribute("href") ?? "").split("/@")[1]?.split(/[/?#]/)[0] ?? "" : "";
      const url = new URL(location.href);
      const userId = url.searchParams.get("u") ?? url.searchParams.get("uid") ?? null;
      // Messages: the chat area's own bubbles, newest last. Skip avatars (small, or inside profile links).
      const items: ({ kind: "text"; text: string } | { kind: "image"; src: string; animated: boolean })[] = [];
      const bubbles = main.querySelectorAll('[data-e2e*="chat-item"], [class*="ChatItem"], [class*="MessageItem"]');
      const scope: Element[] = bubbles.length ? [...bubbles] : [main];
      for (const el of scope.slice(-30)) {
        for (const img of el.querySelectorAll("img")) {
          const r = img.getBoundingClientRect();
          if (img.closest('a[href*="/@"]') || r.width < 56 || r.height < 56) continue;
          const src = img.currentSrc || img.src;
          if (src) items.push({ kind: "image", src, animated: /\.gif|awebp|animated/i.test(src) });
        }
        const text = (el as HTMLElement).innerText?.trim();
        if (text && text.length < 200) items.push({ kind: "text", text });
      }
      return { username, userId, items };
    });
    if (chat.username) chats.push(chat);
  }
  return chats;
}

async function reply(page: Page, text: string) {
  const box = await page.$('[contenteditable="true"], .public-DraftEditor-content, textarea');
  if (!box) return;
  await box.click();
  await page.keyboard.type(text, { delay: 20 });
  await page.keyboard.press("Enter");
  await new Promise((r) => setTimeout(r, 800));
}

export async function run(env: Env): Promise<RunReport> {
  const started = Date.now();
  const report: RunReport = { ok: false, loggedIn: false, chats: 0, sent: 0, results: {}, ms: 0 };
  const cookies = parseCookies(env.TIKTOK_COOKIES ?? "");
  if (!cookies.length) {
    report.error = "TIKTOK_COOKIES secret is not set";
    report.ms = Date.now() - started;
    return report;
  }
  const browser = await puppeteer.launch(env.BROWSER);
  try {
    const page = await browser.newPage();
    await page.setViewport({ width: 1280, height: 900 });
    await page.setCookie(...cookies);
    const chats = await readInbox(page, Number(env.MAX_CHATS || 10));
    report.loggedIn = true;
    report.chats = chats.length;
    for (const chat of chats) {
      for (const msg of await toInbox(chat)) {
        const res = await env.API.fetch("https://api/internal/inbox", {
          method: "POST",
          headers: { authorization: `Bearer ${env.BOT_SECRET}`, "content-type": "application/json" },
          body: JSON.stringify(msg),
        });
        const body = (await res.json().catch(() => ({}))) as { action?: string; reply?: string | null };
        const action = body.action ?? `http_${res.status}`;
        report.results[action] = (report.results[action] ?? 0) + 1;
        report.sent++;
        if (env.SEND_REPLIES === "true" && body.reply && action !== "duplicate") {
          await reply(page, body.reply).catch(() => {});
        }
      }
    }
    report.ok = true;
  } catch (e) {
    report.error = e instanceof Error ? e.message : String(e);
  } finally {
    await browser.close().catch(() => {});
    report.ms = Date.now() - started;
  }
  console.log("bot run", JSON.stringify(report));
  return report;
}

export default {
  async scheduled(_c: ScheduledController, env: Env, ctx: ExecutionContext) {
    ctx.waitUntil(run(env));
  },
  // Manual run for checking the bot: POST /run with the bot secret.
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    if (url.pathname !== "/run" || req.method !== "POST") return new Response("Not found", { status: 404 });
    if (req.headers.get("authorization") !== `Bearer ${env.BOT_SECRET}`) return new Response("Unauthorized", { status: 401 });
    return Response.json(await run(env));
  },
} satisfies ExportedHandler<Env>;
