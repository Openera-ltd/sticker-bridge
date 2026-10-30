/**
 * One pass of the @stickerbridge bot, independent of where the browser runs
 * (Cloudflare's hosted browser in production, a local Chromium in tests).
 */
import type { Page } from "puppeteer-core";
import { ROW_SELECTOR, acceptRequests, readInbox, reply } from "./inbox.ts";
import { parseCookies, toInbox, type Cookie } from "./messages.ts";
import { conversationUsers, extractJsonObjects, outline } from "./imdata.ts";

/** A normal desktop Chrome identity; TikTok holds back its inbox for browsers that say "HeadlessChrome". */
export const USER_AGENT =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";

export interface PassConfig {
  inboxUrl: string;
  maxChats: number;
  sendReplies: boolean;
  /** TIKTOK_COOKIES: used only until a login has been saved on the server. */
  initialCookies: string;
}

/** Calls the API with the bot's key. */
export type Api = (path: string, init?: RequestInit) => Promise<Response>;

/** Anything that can open a page: a puppeteer or @cloudflare/puppeteer browser. */
export interface BrowserLike {
  newPage(): Promise<unknown>;
}

export interface RunReport {
  ok: boolean;
  at: string;
  loggedIn: boolean;
  accepted: number;
  chats: number;
  sent: number;
  results: Record<string, number>;
  error?: string;
  ms: number;
}

/** The login saved on the server wins; TIKTOK_COOKIES is the first-time fallback. */
export async function loadCookies(api: Api, initial: string): Promise<Cookie[]> {
  try {
    const res = await api("/internal/bot-state");
    if (res.ok) {
      const saved = (await res.json()) as { cookies?: Cookie[] };
      if (saved.cookies?.length) return saved.cookies;
    }
  } catch {
    // API unreachable: fall back
  }
  return parseCookies(initial);
}

async function openPage(browser: BrowserLike, cookies: Cookie[]): Promise<Page> {
  const page = (await browser.newPage()) as Page;
  await page.setViewport({ width: 1280, height: 900 });
  await page.setUserAgent(USER_AGENT);
  await page.setCookie(...cookies);
  return page;
}

export async function runPass(browserFactory: () => Promise<BrowserLike>, cfg: PassConfig, api: Api): Promise<RunReport> {
  const started = Date.now();
  const report: RunReport = { ok: false, at: new Date().toISOString(), loggedIn: false, accepted: 0, chats: 0, sent: 0, results: {}, ms: 0 };
  const cookies = await loadCookies(api, cfg.initialCookies);
  if (!cookies.length) {
    report.error = "No TikTok login yet (set TIKTOK_COOKIES)";
    report.ms = Date.now() - started;
    return report;
  }
  let page: Page | null = null;
  try {
    page = await openPage(await browserFactory(), cookies);
    // New people's first messages wait in "Message requests": accept them first.
    report.accepted = await acceptRequests(page, cfg.inboxUrl, cfg.maxChats).catch(() => 0);
    const chats = await readInbox(page, cfg.inboxUrl, cfg.maxChats);
    report.loggedIn = true;
    report.chats = chats.length;
    for (const chat of chats) {
      for (const msg of await toInbox(chat)) {
        const res = await api("/internal/inbox", { method: "POST", body: JSON.stringify(msg) });
        const body = (await res.json().catch(() => ({}))) as { action?: string; reply?: string | null };
        const action = body.action ?? `http_${res.status}`;
        report.results[action] = (report.results[action] ?? 0) + 1;
        report.sent++;
        if (cfg.sendReplies && body.reply && action !== "duplicate") await reply(page, body.reply).catch(() => {});
      }
    }
    // Keep TikTok's refreshed login for next time.
    const fresh = await page.cookies();
    if (fresh.length) await api("/internal/bot-state", { method: "PUT", body: JSON.stringify({ cookies: fresh }) }).catch(() => {});
    report.ok = true;
  } catch (e) {
    report.error = e instanceof Error ? e.message : String(e);
  } finally {
    await page?.close().catch(() => {});
    report.ms = Date.now() - started;
  }
  return report;
}

/** What the bot sees on the inbox page, for adjusting selectors when TikTok changes its layout. */
export async function debugPass(browserFactory: () => Promise<BrowserLike>, cfg: PassConfig, api: Api, waitMs: number, openIndex?: number) {
  const cookies = await loadCookies(api, cfg.initialCookies);
  if (!cookies.length) return { error: "No TikTok login yet" };
  const page = await openPage(await browserFactory(), cookies);
  // Message data TikTok's page downloads (to see whether stickers arrive even when not displayed).
  const network: { url: string; type: string; size: number; sample: string; json?: unknown[] }[] = [];
  page.on("response", async (res) => {
    const url = res.url();
    if (!/im-api|\/im\/|message|conversation|sticker|emoji/i.test(url)) return;
    const raw = await res.buffer().catch(() => null);
    const body = raw ? new TextDecoder().decode(raw) : "";
    const entry: (typeof network)[number] = { url: url.split("?")[0], type: res.headers()["content-type"] ?? "", size: body.length, sample: body.slice(0, 300) };
    if (url.includes("im-api") && raw) {
      // Structure only (keys, types, URL hosts), plus sticker image URLs, so nobody's messages are exposed.
      const objs = extractJsonObjects(raw);
      entry.json = [
        { users: conversationUsers(raw).slice(0, 5), objects: objs.length },
        ...objs.slice(0, 4).map((o) => outline(o)),
        ...objs.filter((o) => JSON.stringify(o).includes("sticker")).slice(0, 2).map((o) => JSON.stringify(o).match(/https?:\/\/[^"]+/g)?.slice(0, 4)),
      ];
    }
    network.push(entry);
  });
  try {
    await page.goto(cfg.inboxUrl, { waitUntil: "domcontentloaded", timeout: 45_000 });
    await new Promise((r) => setTimeout(r, waitMs));
    if (openIndex !== undefined) {
      const rows = await page.$$(ROW_SELECTOR);
      if (rows[openIndex]) {
        await rows[openIndex].click();
        await new Promise((r) => setTimeout(r, 5000));
      }
    }
    const chat = await page.evaluate((sel) => {
      const box = document.querySelector(sel);
      if (!box) return null;
      const e2e: Record<string, number> = {};
      box.querySelectorAll("[data-e2e]").forEach((el) => {
        const k = el.getAttribute("data-e2e") ?? "";
        e2e[k] = (e2e[k] ?? 0) + 1;
      });
      const imgs = [...box.querySelectorAll("img")].map((i) => {
        const r = i.getBoundingClientRect();
        return { src: (i.currentSrc || i.src).split("?")[0], w: Math.round(r.width), h: Math.round(r.height), inProfileLink: Boolean(i.closest('a[href*="/@"]')) };
      });
      const links = [...box.querySelectorAll('a[href*="/@"]')].map((a) => a.getAttribute("href"));
      return { e2e, imgs, links, html: box.innerHTML.length, text: (box as HTMLElement).innerText.slice(0, 600) };
    }, '[data-e2e="dm-new-chatbox"]');
    const info = await page.evaluate(() => {
      const e2e: Record<string, number> = {};
      document.querySelectorAll("[data-e2e]").forEach((el) => {
        const k = el.getAttribute("data-e2e") ?? "";
        e2e[k] = (e2e[k] ?? 0) + 1;
      });
      const classes: Record<string, number> = {};
      document.querySelectorAll("[class]").forEach((el) => {
        for (const c of String(el.getAttribute("class")).split(/\s+/)) {
          const m = c.match(/^css-[a-z0-9]+-(?:Div|Span|A|Img|Button|P|Li|Ul|Main|Header|Section)?([A-Za-z]+)$/);
          if (m) classes[m[1]] = (classes[m[1]] ?? 0) + 1;
        }
      });
      const frames = [...document.querySelectorAll("iframe")].map((f) => f.src);
      return { url: location.href, title: document.title, e2e, classes, frames, text: document.body.innerText.slice(0, 1500) };
    });
    const screenshot = await page.screenshot({ type: "jpeg", quality: 50, encoding: "base64" });
    return { ...info, chat, network, screenshot };
  } finally {
    await page.close().catch(() => {});
  }
}
