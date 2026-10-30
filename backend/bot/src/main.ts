/**
 * The @stickerbridge bot, running on Render.
 *
 * Every POLL_SECONDS it opens TikTok's web inbox (logged in as @stickerbridge),
 * reads recent chats and passes codes and stickers to the API on Cloudflare.
 * The TikTok login is saved on Cloudflare after each run, so a Render restart
 * picks up where it left off. Cloudflare pings /healthz so Render never sleeps.
 */
import { createServer } from "node:http";
import puppeteer, { type Browser, type CookieData } from "puppeteer-core";
import { readInbox, reply } from "./inbox.ts";
import { parseCookies, toInbox } from "./messages.ts";

export interface Config {
  apiBase: string;
  botSecret: string;
  initialCookies: string;
  chromePath: string;
  inboxUrl: string;
  pollSeconds: number;
  maxChats: number;
  sendReplies: boolean;
}

export function configFromEnv(env = process.env): Config {
  return {
    apiBase: (env.API_BASE ?? "https://api.sticker.openera.tech").replace(/\/+$/, ""),
    botSecret: env.BOT_SECRET ?? "",
    initialCookies: env.TIKTOK_COOKIES ?? "",
    chromePath: env.CHROME_PATH ?? "/usr/bin/chromium",
    inboxUrl: env.INBOX_URL ?? "https://www.tiktok.com/messages?lang=en",
    pollSeconds: Math.max(20, Number(env.POLL_SECONDS ?? 60)),
    maxChats: Number(env.MAX_CHATS ?? 10),
    sendReplies: env.SEND_REPLIES === "true",
  };
}

export interface RunReport {
  ok: boolean;
  at: string;
  loggedIn: boolean;
  chats: number;
  sent: number;
  results: Record<string, number>;
  error?: string;
  ms: number;
}

// Chromium flags that keep memory low enough for Render's free 512 MB.
const CHROME_ARGS = [
  "--no-sandbox", "--disable-setuid-sandbox", "--disable-dev-shm-usage", "--disable-gpu",
  "--no-zygote", "--disable-extensions", "--disable-background-networking", "--mute-audio",
  "--renderer-process-limit=1", "--js-flags=--max-old-space-size=192",
];

export class Bot {
  private browser: Browser | null = null;
  private runs = 0;
  private running: Promise<RunReport> | null = null;
  /** Runs and debug views share one browser, so they take turns. */
  private queue: Promise<unknown> = Promise.resolve();

  private exclusive<T>(fn: () => Promise<T>): Promise<T> {
    const next = this.queue.then(fn, fn);
    this.queue = next.catch(() => {});
    return next;
  }
  last: RunReport | null = null;

  private cfg: Config;

  constructor(cfg: Config) {
    this.cfg = cfg;
  }

  private api(path: string, init: RequestInit = {}) {
    return fetch(this.cfg.apiBase + path, {
      ...init,
      headers: { authorization: `Bearer ${this.cfg.botSecret}`, "content-type": "application/json", ...(init.headers ?? {}) },
    });
  }

  /** The login saved on Cloudflare wins; the TIKTOK_COOKIES env var is the first-time fallback. */
  async loadCookies(): Promise<CookieData[]> {
    try {
      const res = await this.api("/internal/bot-state");
      if (res.ok) {
        const saved = (await res.json()) as { cookies?: CookieData[] };
        if (saved.cookies?.length) return saved.cookies;
      }
    } catch {
      // API unreachable: fall back to the env var
    }
    return parseCookies(this.cfg.initialCookies);
  }

  async saveCookies(cookies: CookieData[]) {
    await this.api("/internal/bot-state", { method: "PUT", body: JSON.stringify({ cookies }) }).catch(() => {});
  }

  private async getBrowser(): Promise<Browser> {
    // A fresh browser every 30 runs keeps memory from creeping up.
    if (this.browser && this.runs % 30 === 0) await this.closeBrowser();
    if (!this.browser || !this.browser.connected) {
      this.browser = await puppeteer.launch({ executablePath: this.cfg.chromePath, headless: true, args: CHROME_ARGS });
    }
    return this.browser;
  }

  async closeBrowser() {
    await this.browser?.close().catch(() => {});
    this.browser = null;
  }

  /** What the bot sees on TikTok's inbox page: for adjusting selectors when TikTok changes its layout. */
  debug(): Promise<unknown> {
    return this.exclusive(() => this.doDebug());
  }

  private async doDebug(): Promise<unknown> {
    const cookies = await this.loadCookies();
    if (!cookies.length) return { error: "No TikTok login yet" };
    const browser = await this.getBrowser();
    const page = await browser.newPage();
    try {
      await page.setViewport({ width: 1280, height: 900 });
      await browser.setCookie(...cookies);
      await page.goto(this.cfg.inboxUrl, { waitUntil: "domcontentloaded", timeout: 45_000 });
      await new Promise((r) => setTimeout(r, 12_000));
      const info = await page.evaluate(() => {
        const e2e: Record<string, number> = {};
        document.querySelectorAll("[data-e2e]").forEach((el) => {
          const k = el.getAttribute("data-e2e") ?? "";
          e2e[k] = (e2e[k] ?? 0) + 1;
        });
        const classes: Record<string, number> = {};
        document.querySelectorAll("[class]").forEach((el) => {
          for (const c of String(el.getAttribute("class")).split(/\s+/)) {
            const m = c.match(/^css-[a-z0-9]+-(Div|Span|A|Img|Button|P|Li|Ul|Main|Header|Section)?([A-Za-z]+)$/);
            const key = m ? m[2] : null;
            if (key) classes[key] = (classes[key] ?? 0) + 1;
          }
        });
        return { url: location.href, title: document.title, e2e, classes, text: document.body.innerText.slice(0, 1500) };
      });
      const shot = await page.screenshot({ type: "jpeg", quality: 50, encoding: "base64" });
      return { ...info, screenshot: shot };
    } finally {
      await page.close().catch(() => {});
    }
  }

  /** One pass over the inbox. Never runs twice at the same time. */
  run(): Promise<RunReport> {
    this.running ??= this.exclusive(() => this.doRun()).finally(() => (this.running = null));
    return this.running;
  }

  private async doRun(): Promise<RunReport> {
    const started = Date.now();
    const report: RunReport = { ok: false, at: new Date().toISOString(), loggedIn: false, chats: 0, sent: 0, results: {}, ms: 0 };
    const cookies = await this.loadCookies();
    if (!cookies.length) {
      report.error = "No TikTok login yet (set TIKTOK_COOKIES)";
      report.ms = Date.now() - started;
      return (this.last = report);
    }
    this.runs++;
    let page = null;
    try {
      const browser = await this.getBrowser();
      page = await browser.newPage();
      await page.setViewport({ width: 1280, height: 900 });
      await page.setRequestInterception(true);
      page.on("request", (r) => (["media", "font"].includes(r.resourceType()) ? r.abort() : r.continue()));
      await browser.setCookie(...cookies);

      const chats = await readInbox(page, this.cfg.inboxUrl, this.cfg.maxChats);
      report.loggedIn = true;
      report.chats = chats.length;
      for (const chat of chats) {
        for (const msg of await toInbox(chat)) {
          const res = await this.api("/internal/inbox", { method: "POST", body: JSON.stringify(msg) });
          const body = (await res.json().catch(() => ({}))) as { action?: string; reply?: string | null };
          const action = body.action ?? `http_${res.status}`;
          report.results[action] = (report.results[action] ?? 0) + 1;
          report.sent++;
          if (this.cfg.sendReplies && body.reply && action !== "duplicate") await reply(page, body.reply).catch(() => {});
        }
      }
      await this.saveCookies(await browser.cookies());
      report.ok = true;
    } catch (e) {
      report.error = e instanceof Error ? e.message : String(e);
      await this.closeBrowser(); // start clean next time
    } finally {
      await page?.close().catch(() => {});
      report.ms = Date.now() - started;
    }
    console.log("bot run", JSON.stringify(report));
    return (this.last = report);
  }
}

export function startServer(bot: Bot, cfg: Config, port: number) {
  const server = createServer(async (req, res) => {
    const send = (code: number, body: unknown) => {
      res.writeHead(code, { "content-type": "application/json" });
      res.end(JSON.stringify(body));
    };
    if (req.url === "/healthz" && req.method === "GET") return send(200, { ok: true, last: bot.last });
    if (req.url === "/run" && req.method === "POST") {
      if (req.headers.authorization !== `Bearer ${cfg.botSecret}` || !cfg.botSecret) return send(401, { error: "Unauthorized" });
      return send(200, await bot.run());
    }
    if (req.url === "/debug" && req.method === "POST") {
      if (req.headers.authorization !== `Bearer ${cfg.botSecret}` || !cfg.botSecret) return send(401, { error: "Unauthorized" });
      return send(200, await bot.debug().catch((e) => ({ error: String(e) })));
    }
    send(404, { error: "Not found" });
  });
  server.listen(port);
  return server;
}

// Started directly (not imported by tests): serve, and read the inbox on a timer.
if (import.meta.url === `file://${process.argv[1]}`) {
  const cfg = configFromEnv();
  if (!cfg.botSecret) console.warn("BOT_SECRET is not set: the API will refuse the bot's messages");
  const bot = new Bot(cfg);
  startServer(bot, cfg, Number(process.env.PORT ?? 10000));
  const tick = () => bot.run().catch((e) => console.error(e)).finally(() => setTimeout(tick, cfg.pollSeconds * 1000));
  setTimeout(tick, 3000);
  console.log(`bot up, reading inbox every ${cfg.pollSeconds}s`);
}
