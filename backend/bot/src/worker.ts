/**
 * The @stickerbridge bot on Cloudflare (Workers Paid). Every 2 minutes it opens TikTok's
 * web inbox in Cloudflare's hosted browser, accepts message requests, and passes codes
 * and stickers to the API. The TikTok login is kept on the API (R2), refreshed each run.
 */
import puppeteer from "@cloudflare/puppeteer";
import { debugPass, runPass, type Api, type BrowserLike, type PassConfig, type RunReport } from "./run.ts";

export interface Env {
  BROWSER: Fetcher;
  /** The API worker, reached directly inside Cloudflare. */
  API: Fetcher;
  BOT_SECRET: string;
  /** Optional first-time login; after one good run the saved login is used. */
  TIKTOK_COOKIES?: string;
  MAX_CHATS: string;
  SEND_REPLIES: string;
  INBOX_URL: string;
  /** Last run, for /healthz. */
  STATE: KVNamespace;
}

function setup(env: Env) {
  const api: Api = (path, init = {}) =>
    env.API.fetch("https://api" + path, {
      ...init,
      headers: { authorization: `Bearer ${env.BOT_SECRET}`, "content-type": "application/json", ...(init.headers ?? {}) },
    });
  const cfg: PassConfig = {
    inboxUrl: env.INBOX_URL || "https://www.tiktok.com/messages?lang=en",
    maxChats: Number(env.MAX_CHATS || 10),
    sendReplies: env.SEND_REPLIES === "true",
    initialCookies: env.TIKTOK_COOKIES ?? "",
  };
  let browser: Awaited<ReturnType<typeof puppeteer.launch>> | null = null;
  const factory = async (): Promise<BrowserLike> => (browser ??= await puppeteer.launch(env.BROWSER));
  const close = async () => {
    await browser?.close().catch(() => {});
  };
  return { api, cfg, factory, close };
}

async function run(env: Env): Promise<RunReport> {
  const { api, cfg, factory, close } = setup(env);
  try {
    const report = await runPass(factory, cfg, api);
    console.log("bot run", JSON.stringify(report));
    await env.STATE.put("last", JSON.stringify(report));
    return report;
  } finally {
    await close();
  }
}

export default {
  async scheduled(_c: ScheduledController, env: Env, ctx: ExecutionContext) {
    ctx.waitUntil(run(env));
  },

  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    if (url.pathname === "/healthz") return Response.json({ ok: true, last: JSON.parse((await env.STATE.get("last")) ?? "null") });
    if (req.method !== "POST" || req.headers.get("authorization") !== `Bearer ${env.BOT_SECRET}`) {
      return new Response("Not found", { status: 404 });
    }
    if (url.pathname === "/run") return Response.json(await run(env));
    if (url.pathname === "/debug") {
      const { api, cfg, factory, close } = setup(env);
      try {
        const wait = Math.min(Number(url.searchParams.get("wait") ?? 12), 90) * 1000;
        const open = url.searchParams.get("open");
        return Response.json(await debugPass(factory, cfg, api, wait, open === null ? undefined : Number(open)).catch((e) => ({ error: String(e) })));
      } finally {
        await close();
      }
    }
    return new Response("Not found", { status: 404 });
  },
} satisfies ExportedHandler<Env>;
