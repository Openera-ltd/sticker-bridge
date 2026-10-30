/**
 * Runs the real bot in a real Chromium against a fake TikTok inbox and a fake API.
 * Skipped when no Chromium is available (set CHROME_PATH).
 */
import { createServer, type Server } from "node:http";
import { existsSync } from "node:fs";
import type { AddressInfo } from "node:net";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { Bot, type Config } from "./main.ts";

const CHROME = process.env.CHROME_PATH ?? "/opt/pw-browsers/chromium-1243/chrome-linux64/chrome";

let accepted = false;
const inbox = () => `<!doctype html><html><body>
<div id="list">
  <div class="requests-entry" onclick="showRequests()"><span>Message requests</span></div>
  <div data-e2e="chat-list-item" onclick="openChat('mia.draws')">Mia</div>
  <div data-e2e="chat-list-item" onclick="openChat('sam_1')">Sam</div>
  ${accepted ? `<div data-e2e="chat-list-item" onclick="openChat('new.person')">New</div>` : ""}
</div>
<main data-e2e="chat-main" id="main"></main>
<script>
  const chats = {
    'mia.draws': '<div data-e2e="chat-item"><a href="/@mia.draws"><img width="32" height="32" src="https://p16-sign.tiktokcdn.com/avatar.jpg"></a>my code SB-7KQ4MZ</div>'
      + '<div data-e2e="chat-item"><img width="120" height="120" src="https://p16-sign.tiktokcdn.com/sticker/one.webp?sig=a"></div>',
    'new.person': '<div data-e2e="chat-item">SB-ABCDEF</div>',
    'sam_1': '<div data-e2e="chat-item"><a href="/@sam_1">Sam</a></div>'
      + '<div data-e2e="chat-item"><img width="140" height="140" src="https://p16-sign.tiktokcdn.com/sticker/two.gif?sig=b"></div>'
      + '<div data-e2e="chat-item">lol</div>',
  };
  function showRequests() {
    document.getElementById('list').innerHTML = ${accepted ? "''" : `'<div data-e2e="chat-list-item" onclick="openRequest()">new.person</div>'`};
  }
  function openRequest() {
    document.getElementById('main').innerHTML = '<p>new.person wants to send you a message</p><button onclick="fetch(\\'/accept\\', {method:\\'POST\\'}).then(() => { document.getElementById(\\'list\\').innerHTML = \\'\\'; })">Accept</button><button>Delete</button>';
  }
  function openChat(u) {
    history.replaceState(null, '', '/messages?u=' + (u === 'mia.draws' ? '7012345678901234567' : ''));
    document.getElementById('main').innerHTML = '<header><a href="/@' + u + '">@' + u + '</a></header>' + chats[u]
      + '<div contenteditable="true" id="box"></div>';
    document.getElementById('box').addEventListener('keydown', (e) => {
      if (e.key === 'Enter') { e.preventDefault(); fetch('/typed', { method: 'POST', body: e.target.innerText.trim() }); e.target.innerText = ''; }
    });
  }
</script></body></html>`;

let tiktok: Server, api: Server;
let tiktokUrl = "", apiUrl = "";
const posted: any[] = [];
const typed: string[] = [];
let savedState: any = null;
let cookiesSeen = "";

function listen(s: Server) {
  return new Promise<string>((r) => s.listen(0, "127.0.0.1", () => r(`http://127.0.0.1:${(s.address() as AddressInfo).port}`)));
}
const body = (req: any) => new Promise<string>((r) => { let b = ""; req.on("data", (c: any) => (b += c)); req.on("end", () => r(b)); });

beforeAll(async () => {
  tiktok = createServer(async (req, res) => {
    if (req.url?.startsWith("/messages")) {
      cookiesSeen = req.headers.cookie ?? "";
      res.writeHead(200, { "content-type": "text/html" });
      return res.end(inbox());
    }
    if (req.url === "/accept") { accepted = true; return res.end("ok"); }
    if (req.url === "/typed") { typed.push(await body(req)); return res.end("ok"); }
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
      return res.end(JSON.stringify({ action: m.type === "text" ? "linked" : "stored", reply: m.type === "text" ? "Connected" : "Got it" }));
    }
    res.writeHead(404); res.end();
  });
  [tiktokUrl, apiUrl] = await Promise.all([listen(tiktok), listen(api)]);
});

afterAll(() => { tiktok?.close(); api?.close(); });

const cfg = (): Config => ({
  apiBase: apiUrl, botSecret: "s3cret", initialCookies: JSON.stringify([{ name: "sessionid", value: "first-login", domain: "localhost" }]),
  chromePath: CHROME, inboxUrl: `${tiktokUrl}/messages?lang=en`, pollSeconds: 60, maxChats: 10, sendReplies: true,
});

describe.skipIf(!existsSync(CHROME))("bot in a real browser", () => {
  it("reads chats, posts codes and stickers, replies, and saves its login", async () => {
    const bot = new Bot({ ...cfg(), inboxUrl: cfg().inboxUrl.replace("127.0.0.1", "localhost") });
    const r = await bot.run();
    await bot.closeBrowser();
    expect(r.error).toBeUndefined();
    expect(r).toMatchObject({ ok: true, loggedIn: true, accepted: 1, chats: 3, sent: 4 });
    // The accepted request's code reached the API in the same run.
    expect(posted.find((m) => m.text === "SB-ABCDEF")?.sender.username).toBe("new.person");
    expect(cookiesSeen).toContain("sessionid=first-login");

    const code = posted.find((m) => m.type === "text");
    expect(code).toMatchObject({ text: "my code SB-7KQ4MZ", sender: { id: "7012345678901234567", username: "mia.draws" } });
    const stickers = posted.filter((m) => m.type === "sticker");
    expect(stickers.map((s) => s.sender.username)).toEqual(["mia.draws", "sam_1"]);
    expect(stickers[1]).toMatchObject({ animated: true, sender: { id: "u_sam_1" } }); // no id on the page: username fallback
    expect(stickers.every((s) => !s.url.includes("avatar"))).toBe(true);

    expect(typed).toEqual(["Connected", "Got it", "Got it", "Connected"]);
    expect(savedState.cookies.some((c: any) => c.name === "sessionid" && c.value === "first-login")).toBe(true);
  }, 90_000);

  it("prefers the login saved on the server after a restart", async () => {
    savedState = { cookies: [{ name: "sessionid", value: "refreshed", domain: "localhost", path: "/" }] };
    const bot = new Bot({ ...cfg(), inboxUrl: cfg().inboxUrl.replace("127.0.0.1", "localhost"), sendReplies: false });
    const r = await bot.run();
    await bot.closeBrowser();
    expect(r.ok).toBe(true);
    expect(cookiesSeen).toContain("sessionid=refreshed");
  }, 90_000);

  it("does nothing without a login", async () => {
    savedState = null;
    const bot = new Bot({ ...cfg(), initialCookies: "" });
    const r = await bot.run();
    expect(r).toMatchObject({ ok: false, error: "No TikTok login yet (set TIKTOK_COOKIES)" });
  });

  it("reports a clear error when the inbox can't be read (e.g. logged out)", async () => {
    const bot = new Bot({ ...cfg(), inboxUrl: `${tiktokUrl}/nope` });
    const r = await bot.run();
    await bot.closeBrowser();
    expect(r.ok).toBe(false);
    expect(r.loggedIn).toBe(false);
    expect(r.error).toMatch(/Waiting for selector/);
  }, 90_000);
});
