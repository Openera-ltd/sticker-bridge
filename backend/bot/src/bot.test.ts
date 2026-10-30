/**
 * Runs a real bot pass in a real Chromium against a fake TikTok inbox and a fake API.
 * The fake inbox behaves like TikTok's: the page shows "message type isn't supported",
 * and the stickers only arrive in the im-api message data it downloads.
 * Skipped when no Chromium is available (set CHROME_PATH).
 */
import { createServer, type Server } from "node:http";
import { existsSync } from "node:fs";
import type { AddressInfo } from "node:net";
import puppeteer from "puppeteer-core";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { runPass, type Api, type PassConfig } from "./run.ts";
import { stickerMsg, textMsg } from "./imdata.test.ts";

const CHROME = process.env.CHROME_PATH ?? "/opt/pw-browsers/chromium-1243/chrome-linux64/chrome";
const BOT = "7604267844595581974";
const MIA = "7548886268853404727";
const NEW = "7111111111111111111";
const STICKER2 = "https://p16-tiktok-dm-sticker-sign-useastred.tiktokcdn-eu.com/tos-useast2a/3e85f70ec853~tplv-video2sticker-mid.awebp?x-signature=abc";

let accepted = false;
const page = () => `<!doctype html><html><body>
<div id="list">
  <div class="requests-entry" onclick="showRequests()"><span>Message requests</span></div>
  <div data-e2e="dm-new-conversation-item">Mia · This message type isn’t supported.</div>
</div>
<main data-e2e="dm-new-chatbox" id="main"></main>
<script>
  fetch('/im-api/v2/message/get_by_user_init');
  fetch('/tiktok/v1/im/user/profile/');
  function showRequests() {
    document.getElementById('list').innerHTML = ${accepted ? "''" : `'<div data-e2e="dm-new-conversation-item" onclick="openRequest()">new.person</div>'`};
  }
  function openRequest() {
    document.getElementById('main').innerHTML = '<button onclick="fetch(\\'/accept\\', {method:\\'POST\\'}).then(() => { document.getElementById(\\'list\\').innerHTML = \\'\\'; })">Accept</button><button>Delete</button>';
  }
</script></body></html>`;

function imData(): Buffer {
  const msgs = [
    ...stickerMsg("de3294a0-9d81-41e9-abde-69020ce778ba", undefined, MIA),
    ...stickerMsg("2aa15f0c-1dac-416a-9e0c-ea7c80ec96a3", STICKER2, MIA),
    ...textMsg("69582e88-8c84-47eb-9e73-0d044e207bf0", "lol", MIA),
    ...(accepted ? textMsg("00c056db-f156-49eb-8f7f-e17a3aebbfaf", "my code SB-ABCDEF", NEW) : []),
  ];
  return Buffer.from(Uint8Array.from([8, 1, ...msgs]));
}

let tiktok: Server, api: Server;
let tiktokUrl = "", apiUrl = "";
const posted: any[] = [];
let savedState: any = null;
let cookiesSeen = "";

function listen(s: Server) {
  return new Promise<string>((r) => s.listen(0, "127.0.0.1", () => r(`http://localhost:${(s.address() as AddressInfo).port}`)));
}
const body = (req: any) => new Promise<string>((r) => { let b = ""; req.on("data", (c: any) => (b += c)); req.on("end", () => r(b)); });

beforeAll(async () => {
  tiktok = createServer(async (req, res) => {
    if (req.url?.startsWith("/messages")) {
      cookiesSeen = req.headers.cookie ?? "";
      res.writeHead(200, { "content-type": "text/html" });
      return res.end(page());
    }
    if (req.url?.startsWith("/im-api/")) {
      res.writeHead(200, { "content-type": "application/x-protobuf" });
      return res.end(imData());
    }
    if (req.url?.startsWith("/tiktok/v1/im/user/profile")) {
      res.writeHead(200, { "content-type": "application/json" });
      return res.end(JSON.stringify({ users: [{ uid: MIA, unique_id: "mia.draws" }] }));
    }
    if (req.url === "/accept") { accepted = true; return res.end("ok"); }
    res.writeHead(404); res.end();
  });
  api = createServer(async (req, res) => {
    if (req.headers.authorization !== "Bearer s3cret") { res.writeHead(401); return res.end("{}"); }
    if (req.url === "/internal/bot-state" && req.method === "GET") {
      res.writeHead(savedState ? 200 : 404, { "content-type": "application/json" });
      return res.end(JSON.stringify(savedState ?? {}));
    }
    if (req.url === "/internal/bot-state" && req.method === "PUT") { savedState = JSON.parse(await body(req)); return res.end("{}"); }
    if (req.url === "/internal/inbox") {
      const m = JSON.parse(await body(req));
      posted.push(m);
      res.writeHead(200, { "content-type": "application/json" });
      return res.end(JSON.stringify({ action: m.type === "text" ? "linked" : "stored" }));
    }
    res.writeHead(404); res.end();
  });
  [tiktokUrl, apiUrl] = await Promise.all([listen(tiktok), listen(api)]);
});

afterAll(() => { tiktok?.close(); api?.close(); });

const login = (session = "first-login") => JSON.stringify([
  { name: "sessionid", value: session, domain: "localhost" },
  { name: "multi_sids", value: encodeURIComponent(`${MIA}:other|${BOT}:${session}`), domain: "localhost" },
]);

const cfg = (over: Partial<PassConfig> = {}): PassConfig => ({
  initialCookies: login(), inboxUrl: `${tiktokUrl}/messages?lang=en`, maxChats: 10, sendReplies: false, settleMs: 1500, ...over,
});

async function pass(c: PassConfig) {
  let browser: Awaited<ReturnType<typeof puppeteer.launch>> | null = null;
  const callApi: Api = (path, init = {}) =>
    fetch(apiUrl + path, { ...init, headers: { authorization: "Bearer s3cret", "content-type": "application/json" } });
  try {
    return await runPass(async () => (browser ??= await puppeteer.launch({ executablePath: CHROME, headless: true, args: ["--no-sandbox"] })), c, callApi);
  } finally {
    await (browser as any)?.close();
  }
}

describe.skipIf(!existsSync(CHROME))("bot in a real browser", () => {
  it("accepts requests, then takes stickers and codes from TikTok's message data", async () => {
    const r = await pass(cfg());
    expect(r.error).toBeUndefined();
    expect(r).toMatchObject({ ok: true, loggedIn: true, accepted: 1, chats: 2, sent: 3 });
    expect(cookiesSeen).toContain("sessionid=first-login");

    const stickers = posted.filter((m) => m.type === "sticker");
    expect(stickers).toHaveLength(2);
    expect(stickers[0]).toMatchObject({ messageId: "tt:de3294a0-9d81-41e9-abde-69020ce778ba", sender: { id: MIA, username: "mia.draws" } });
    expect(stickers[0].url).toMatch(/^https:\/\/p16-tiktok-dm-sticker-sign-sg\.ibyteimg\.com\/.+x-signature=/);
    expect(stickers[1].url).toBe(STICKER2);
    // "lol" isn't a code, so it isn't sent; the new person's code is, with an id-based name.
    const codes = posted.filter((m) => m.type === "text");
    expect(codes).toEqual([{ messageId: "tt:00c056db-f156-49eb-8f7f-e17a3aebbfaf", sender: { id: NEW, username: `u_${NEW}` }, type: "text", text: "my code SB-ABCDEF" }]);
    expect(savedState.cookies.some((c: any) => c.name === "sessionid")).toBe(true);
  }, 90_000);

  it("prefers the login saved on the server", async () => {
    savedState = { cookies: JSON.parse(login("refreshed")).map((c: any) => ({ ...c, path: "/" })) };
    const r = await pass(cfg());
    expect(r.ok).toBe(true);
    expect(cookiesSeen).toContain("sessionid=refreshed");
  }, 90_000);

  it("does nothing without a login", async () => {
    savedState = null;
    const r = await pass(cfg({ initialCookies: "" }));
    expect(r).toMatchObject({ ok: false, error: "No TikTok login yet (set TIKTOK_COOKIES)" });
  });

  it("reports a clear error when the inbox doesn't load (e.g. logged out)", async () => {
    const r = await pass(cfg({ inboxUrl: `${tiktokUrl}/nope` }));
    expect(r.ok).toBe(false);
    expect(r.loggedIn).toBe(false);
    expect(r.error).toMatch(/Waiting for selector/);
  }, 90_000);
});
