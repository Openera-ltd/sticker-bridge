/**
 * The live @stickerbridge bot for Render's free plan (0.1 CPU, 512 MB).
 *
 * Instead of reloading TikTok for every check, it opens ONE tab on tiktok.com/messages and keeps it
 * open. TikTok pushes new messages to that tab over its live connection (WebSocket); the bot reads
 * stickers and SB- codes straight from that data and hands them to the API on Cloudflare. Heavy
 * things the tab doesn't need (images, video, fonts, live streams, trackers) are never loaded.
 */
import { createServer } from "node:http";
import puppeteer, { type Browser, type CDPSession, type Page } from "puppeteer-core";
import { ROW_SELECTOR, acceptRequests } from "./inbox.ts";
import { botIdFromCookies, parseImMessages, usernamesFrom, type ImMessage } from "./imdata.ts";
import { loadCookies, USER_AGENT, type Api } from "./run.ts";
import type { InboxMessage } from "./messages.ts";

const CODE = /SB\s*-?\s*[23456789A-HJ-NP-Z]{6}/i;

export interface LiveConfig {
  apiBase: string;
  botSecret: string;
  initialCookies: string;
  chromePath: string;
  inboxUrl: string;
  /** How long the first load of TikTok may take on a slow CPU. */
  loadTimeoutMs: number;
  /** How often to look for message requests (in place, no reload). */
  requestsEveryMs: number;
  /** Reload the tab if no message data has arrived for this long (safety net). */
  staleAfterMs: number;
  /** Start a fresh browser this often, to keep memory from creeping. */
  restartEveryMs: number;
}

export function configFromEnv(env = process.env): LiveConfig {
  const min = (v: string | undefined, d: number) => Number(v ?? d) * 60_000;
  return {
    apiBase: (env.API_BASE ?? "https://api.sticker.openera.tech").replace(/\/+$/, ""),
    botSecret: env.BOT_SECRET ?? "",
    initialCookies: env.TIKTOK_COOKIES ?? "",
    chromePath: env.CHROME_PATH ?? "/usr/local/bin/chrome-headless-shell",
    inboxUrl: env.INBOX_URL ?? "https://www.tiktok.com/messages?lang=en",
    loadTimeoutMs: Number(env.LOAD_TIMEOUT_S ?? 240) * 1000,
    requestsEveryMs: min(env.REQUESTS_EVERY_MIN, 2),
    staleAfterMs: min(env.STALE_AFTER_MIN, 20),
    restartEveryMs: min(env.RESTART_EVERY_MIN, 360),
  };
}

// Lean Chromium: one renderer, no GPU, no background work, small JS heap.
const CHROME_ARGS = [
  "--no-sandbox", "--disable-setuid-sandbox", "--disable-dev-shm-usage", "--disable-gpu", "--no-zygote",
  "--disable-extensions", "--disable-background-networking", "--disable-background-timer-throttling",
  "--disable-backgrounding-occluded-windows", "--disable-renderer-backgrounding", "--disable-component-update",
  "--disable-default-apps", "--disable-sync", "--metrics-recording-only", "--mute-audio", "--no-first-run",
  "--renderer-process-limit=1", "--disable-site-isolation-trials",
  "--disable-features=Translate,MediaRouter,OptimizationHints,site-per-process,IsolateOrigins,BackForwardCache",
  "--js-flags=--max-old-space-size=256", "--disable-blink-features=AutomationControlled",
  "--blink-settings=imagesEnabled=false", "--lang=en-GB", "--window-size=1024,700",
];

/** Things the messages tab never needs. Blocking them saves most of the CPU and memory. */
const BLOCKED_TYPES = new Set(["image", "media", "font", "texttrack", "eventsource", "manifest", "other"]);
const BLOCKED_HOSTS = [
  /(^|\.)webcast\./, /(^|\.)mon[a-z0-9-]*\.tiktokv/, /(^|\.)mcs[a-z0-9-]*\.tiktok/, /analytics/, /doubleclick/,
  /google/, /facebook/, /(^|\.)log[a-z0-9-]*\.tiktok/, /\.ttwstatic\.com\/.*(live|shop)/i, /tiktokcdn[^/]*\/.*\.(mp4|m3u8|webm)/,
];

export type Status = "starting" | "loading" | "live" | "restarting" | "stopped" | "no-login";

export class LiveInbox {
  status: Status = "starting";
  startedAt = Date.now();
  lastDataAt = 0;
  lastError: string | null = null;
  counts = { frames: 0, responses: 0, messages: 0, posted: 0, accepted: 0, restarts: 0 };
  results: Record<string, number> = {};
  private browser: Browser | null = null;
  private page: Page | null = null;
  private cdp: CDPSession | null = null;
  private seen = new Set<string>();
  private usernames = new Map<string, string>();
  private botId: string | undefined;
  private queue: Promise<void> = Promise.resolve();
  private timers: ReturnType<typeof setInterval>[] = [];
  private browserStartedAt = 0;
  private busy = false;
  private cfg: LiveConfig;
  /** Last few raw payloads (sizes only) for /debug. */
  recent: { at: string; kind: string; bytes: number; found: number }[] = [];

  constructor(cfg: LiveConfig) {
    this.cfg = cfg;
  }

  api: Api = (path, init = {}) =>
    fetch(this.cfg.apiBase + path, {
      ...init,
      headers: { authorization: `Bearer ${this.cfg.botSecret}`, "content-type": "application/json", ...(init.headers ?? {}) },
    });

  log(...a: unknown[]) {
    console.log(new Date().toISOString(), ...a);
  }

  /** Opens the one messages tab and keeps it live. */
  async start(): Promise<void> {
    this.status = "loading";
    const cookies = await loadCookies(this.api, this.cfg.initialCookies);
    if (!cookies.length) {
      this.status = "no-login";
      this.log("no TikTok login yet (set TIKTOK_COOKIES)");
      return;
    }
    this.botId = botIdFromCookies(cookies);
    this.browser = await puppeteer.launch({ executablePath: this.cfg.chromePath, headless: true, args: CHROME_ARGS, protocolTimeout: 300_000 });
    this.browserStartedAt = Date.now();
    this.browser.on("disconnected", () => {
      if (this.status === "live" || this.status === "loading") this.fail("browser closed");
    });
    const page = (this.page = (await this.browser.pages())[0] ?? (await this.browser.newPage()));
    await page.setViewport({ width: 1024, height: 700 });
    await page.setUserAgent(USER_AGENT);
    await this.browser.setCookie(...cookies);

    await page.setRequestInterception(true);
    page.on("request", (req) => {
      const url = req.url();
      let host = "";
      try { host = new URL(url).hostname; } catch { /* data: urls */ }
      if (BLOCKED_TYPES.has(req.resourceType()) || BLOCKED_HOSTS.some((re) => re.test(host) || re.test(url))) {
        req.abort().catch(() => {});
      } else {
        req.continue().catch(() => {});
      }
    });
    page.on("response", (res) => void this.onResponse(res.url(), res).catch(() => {}));
    page.on("error", (e) => this.fail(`page crashed: ${e.message}`));

    // Messages TikTok pushes to the open tab arrive on its WebSocket.
    this.cdp = await page.createCDPSession();
    await this.cdp.send("Network.enable");
    this.cdp.on("Network.webSocketFrameReceived", (e: { response: { opcode: number; payloadData: string } }) => {
      const data = e.response.opcode === 2 ? Buffer.from(e.response.payloadData, "base64") : Buffer.from(e.response.payloadData);
      this.counts.frames++;
      this.handle(new Uint8Array(data), "ws");
    });

    this.log("loading tiktok messages (one tab, images/media blocked)…");
    await page.goto(this.cfg.inboxUrl, { waitUntil: "domcontentloaded", timeout: this.cfg.loadTimeoutMs });
    await page.waitForSelector(ROW_SELECTOR, { timeout: this.cfg.loadTimeoutMs });
    this.status = "live";
    this.lastDataAt = Date.now();
    this.log(`live after ${Math.round((Date.now() - this.startedAt) / 1000)}s`);

    this.timers.push(setInterval(() => void this.housekeeping(), Math.min(this.cfg.requestsEveryMs, 60_000)));
    await this.saveLogin();
  }

  private async onResponse(url: string, res: { buffer(): Promise<Buffer>; json(): Promise<unknown> }) {
    if (/im-api[^/]*\/v\d\/message\//.test(url) || url.includes("/im-api/")) {
      this.counts.responses++;
      this.handle(new Uint8Array(await res.buffer()), "http");
    } else if (/\/im\/user\/profile|\/api\/user\/|\/im\/spotlight/.test(url)) {
      usernamesFrom(await res.json(), this.usernames);
    } else if (url.includes("/passport/web/account/info")) {
      this.botId ??= ((await res.json()) as { data?: { user_id_str?: string } }).data?.user_id_str;
    }
  }

  /** Reads stickers and codes from a payload and queues new ones for the API. */
  handle(bytes: Uint8Array, kind: string): number {
    const found = parseImMessages(bytes, this.botId).filter((m) => !this.seen.has(m.messageId));
    this.recent.unshift({ at: new Date().toISOString(), kind, bytes: bytes.length, found: found.length });
    this.recent.length = Math.min(this.recent.length, 30);
    if (bytes.length > 64) this.lastDataAt = Date.now();
    for (const m of found) {
      this.seen.add(m.messageId);
      this.counts.messages++;
      this.queue = this.queue.then(() => this.post(m)).catch(() => {});
    }
    if (this.seen.size > 20_000) this.seen = new Set([...this.seen].slice(-10_000));
    return found.length;
  }

  private async post(m: ImMessage) {
    if (m.kind === "text" && !CODE.test(m.text ?? "")) return; // only codes matter
    const msg: InboxMessage = {
      messageId: `tt:${m.messageId}`,
      sender: { id: m.senderId, username: this.usernames.get(m.senderId) ?? `u_${m.senderId}` },
      type: m.kind,
      ...(m.kind === "text" ? { text: m.text } : { url: m.url }),
    };
    for (let attempt = 0; attempt < 4; attempt++) {
      try {
        const res = await this.api("/internal/inbox", { method: "POST", body: JSON.stringify(msg) });
        const body = (await res.json().catch(() => ({}))) as { action?: string };
        const action = body.action ?? `http_${res.status}`;
        this.results[action] = (this.results[action] ?? 0) + 1;
        this.counts.posted++;
        if (action !== "duplicate") this.log(`${m.kind} from ${msg.sender.username}: ${action}`);
        if (res.status < 500) return;
      } catch (e) {
        this.lastError = `api: ${String(e)}`;
      }
      await new Promise((r) => setTimeout(r, 2000 * (attempt + 1)));
    }
  }

  /** Every minute: accept requests, keep the tab fresh, restart now and then. */
  private async housekeeping() {
    if (this.busy || this.status !== "live" || !this.page) return;
    this.busy = true;
    try {
      const now = Date.now();
      if (now - this.browserStartedAt > this.cfg.restartEveryMs) return void (await this.restart("scheduled fresh start"));
      if (now - this.lastDataAt > this.cfg.staleAfterMs) {
        this.log("no message data for a while: reloading the tab");
        await this.page.reload({ waitUntil: "domcontentloaded", timeout: this.cfg.loadTimeoutMs });
        await this.page.waitForSelector(ROW_SELECTOR, { timeout: this.cfg.loadTimeoutMs });
        this.lastDataAt = Date.now();
        await this.saveLogin();
      }
      const accepted = await acceptRequests(this.page, this.cfg.inboxUrl, 10, { inPlace: true }).catch(() => 0);
      if (accepted > 0) {
        this.counts.accepted += accepted;
        this.log(`accepted ${accepted} message request(s)`);
        // Back to the main inbox; its data (with the new chats) arrives in the response handler.
        await this.page.goBack({ waitUntil: "domcontentloaded" }).catch(() => {});
        if (!(await this.page.$(ROW_SELECTOR))) {
          await this.page.goto(this.cfg.inboxUrl, { waitUntil: "domcontentloaded", timeout: this.cfg.loadTimeoutMs });
          await this.page.waitForSelector(ROW_SELECTOR, { timeout: this.cfg.loadTimeoutMs });
        }
      }
    } catch (e) {
      this.fail(`housekeeping: ${e instanceof Error ? e.message : String(e)}`);
    } finally {
      this.busy = false;
    }
  }

  private async saveLogin() {
    const fresh = await this.browser?.cookies().catch(() => []);
    if (fresh?.length) await this.api("/internal/bot-state", { method: "PUT", body: JSON.stringify({ cookies: fresh }) }).catch(() => {});
  }

  private failing = false;
  fail(reason: string) {
    this.lastError = reason;
    this.log("problem:", reason);
    if (this.failing) return;
    this.failing = true;
    setTimeout(() => void this.restart(reason).finally(() => (this.failing = false)), 15_000);
  }

  async restart(reason: string) {
    this.log("restarting:", reason);
    this.status = "restarting";
    this.counts.restarts++;
    await this.stop();
    this.startedAt = Date.now();
    await this.start().catch((e) => this.fail(`start: ${e instanceof Error ? e.message : String(e)}`));
  }

  async stop() {
    this.timers.forEach(clearInterval);
    this.timers = [];
    await this.saveLogin().catch(() => {});
    const b = this.browser;
    this.browser = null;
    this.page = null;
    this.cdp = null;
    this.status = "stopped";
    await b?.close().catch(() => {});
  }

  /** What /healthz shows. */
  health() {
    const mem = process.memoryUsage();
    return {
      ok: this.status === "live",
      status: this.status,
      uptimeS: Math.round((Date.now() - this.startedAt) / 1000),
      lastDataAgoS: this.lastDataAt ? Math.round((Date.now() - this.lastDataAt) / 1000) : null,
      botId: this.botId ?? null,
      counts: this.counts,
      results: this.results,
      lastError: this.lastError,
      nodeMemMB: Math.round(mem.rss / 1e6),
    };
  }

  async screenshot(): Promise<string | null> {
    return (await this.page?.screenshot({ type: "jpeg", quality: 40, encoding: "base64" }).catch(() => null)) ?? null;
  }
}

/** Total memory used by the container (this process and Chromium), from cgroups. */
async function containerMemMB(): Promise<number | null> {
  const { readFile } = await import("node:fs/promises");
  for (const f of ["/sys/fs/cgroup/memory.current", "/sys/fs/cgroup/memory/memory.usage_in_bytes"]) {
    const v = await readFile(f, "utf8").catch(() => null);
    if (v) return Math.round(Number(v) / 1e6);
  }
  return null;
}

export function startServer(bot: LiveInbox, cfg: LiveConfig, port: number) {
  const authed = (h: string | undefined) => Boolean(cfg.botSecret) && h === `Bearer ${cfg.botSecret}`;
  return createServer(async (req, res) => {
    const send = (code: number, body: unknown) => {
      res.writeHead(code, { "content-type": "application/json" });
      res.end(JSON.stringify(body));
    };
    const url = new URL(req.url ?? "/", "http://x");
    if (url.pathname === "/healthz") return send(200, { ...bot.health(), containerMemMB: await containerMemMB() });
    if (!authed(req.headers.authorization)) return send(404, { error: "Not found" });
    if (url.pathname === "/debug") {
      return send(200, { ...bot.health(), recent: bot.recent, screenshot: url.searchParams.has("shot") ? await bot.screenshot() : undefined });
    }
    if (url.pathname === "/restart" && req.method === "POST") {
      void bot.restart("requested");
      return send(202, { ok: true });
    }
    send(404, { error: "Not found" });
  }).listen(port);
}

// Started directly: serve health, then open the live tab.
if (import.meta.url === `file://${process.argv[1]}`) {
  const cfg = configFromEnv();
  const bot = new LiveInbox(cfg);
  startServer(bot, cfg, Number(process.env.PORT ?? 10000));
  bot.log("bot up");
  bot.start().catch((e) => bot.fail(`start: ${e instanceof Error ? e.message : String(e)}`));
  const shutdown = () => void bot.stop().finally(() => process.exit(0));
  process.on("SIGTERM", shutdown);
  process.on("SIGINT", shutdown);
}
