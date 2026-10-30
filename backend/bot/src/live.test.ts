/**
 * The live bot in a real Chromium: one tab stays open, and a sticker pushed later over the page's
 * WebSocket (like TikTok's live message push) reaches the API without any reload.
 */
import { createHash } from "node:crypto";
import { existsSync } from "node:fs";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import type { Duplex } from "node:stream";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { LiveInbox, type LiveConfig } from "./live.ts";
import { stickerMsg, textMsg } from "./imdata.test.ts";

const CHROME = process.env.CHROME_PATH ?? "/opt/pw-browsers/chromium-1243/chrome-linux64/chrome";
const BOT = "7604267844595581974";
const MIA = "7548886268853404727";

const PAGE = `<!doctype html><html><body>
<div data-e2e="dm-new-conversation-item">Mia</div>
<img src="/big-image.jpg">
<script>
  fetch('/im-api/v2/message/get_by_user_init');
  const ws = new WebSocket('ws://' + location.host + '/ws');
  ws.binaryType = 'arraybuffer';
</script></body></html>`;

/** A minimal WebSocket server: enough to push binary frames to the page. */
function wsFrame(payload: Buffer): Buffer {
  const head = payload.length < 126 ? Buffer.from([0x82, payload.length]) : Buffer.from([0x82, 126, payload.length >> 8, payload.length & 0xff]);
  return Buffer.concat([head, payload]);
}

let tiktok: Server, api: Server, tiktokUrl = "", apiUrl = "";
let socket: Duplex | null = null;
const posted: any[] = [];
const requested: string[] = [];

const listen = (s: Server) =>
  new Promise<string>((r) => s.listen(0, "127.0.0.1", () => r(`http://localhost:${(s.address() as AddressInfo).port}`)));
const body = (req: any) => new Promise<string>((r) => { let b = ""; req.on("data", (c: any) => (b += c)); req.on("end", () => r(b)); });

beforeAll(async () => {
  tiktok = createServer((req, res) => {
    requested.push(req.url ?? "");
    if (req.url?.startsWith("/messages")) { res.writeHead(200, { "content-type": "text/html" }); return res.end(PAGE); }
    if (req.url?.startsWith("/im-api/")) {
      res.writeHead(200, { "content-type": "application/x-protobuf" });
      return res.end(Buffer.from(Uint8Array.from(stickerMsg("11111111-1111-4111-8111-111111111111", undefined, MIA))));
    }
    res.writeHead(404); res.end();
  });
  tiktok.on("upgrade", (req, sock) => {
    const accept = createHash("sha1").update(req.headers["sec-websocket-key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").digest("base64");
    sock.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);
    socket = sock;
  });
  api = createServer(async (req, res) => {
    if (req.headers.authorization !== "Bearer s3cret") { res.writeHead(401); return res.end("{}"); }
    if (req.url === "/internal/bot-state") {
      if (req.method === "PUT") await body(req);
      res.writeHead(404); return res.end("{}");
    }
    if (req.url === "/internal/inbox") {
      posted.push(JSON.parse(await body(req)));
      res.writeHead(200, { "content-type": "application/json" });
      return res.end(JSON.stringify({ action: "stored" }));
    }
    res.writeHead(404); res.end();
  });
  [tiktokUrl, apiUrl] = await Promise.all([listen(tiktok), listen(api)]);
});

afterAll(() => { tiktok?.close(); api?.close(); });

const until = async (fn: () => boolean, ms = 15_000) => {
  const end = Date.now() + ms;
  while (!fn() && Date.now() < end) await new Promise((r) => setTimeout(r, 100));
  return fn();
};

describe.skipIf(!existsSync(CHROME))("live bot", () => {
  it("keeps one tab open and picks up stickers pushed over the live connection", async () => {
    const cfg: LiveConfig = {
      apiBase: apiUrl, botSecret: "s3cret", chromePath: CHROME, inboxUrl: `${tiktokUrl}/messages?lang=en`,
      initialCookies: JSON.stringify([
        { name: "sessionid", value: "abc", domain: "localhost" },
        { name: "multi_sids", value: encodeURIComponent(`${BOT}:abc`), domain: "localhost" },
      ]),
      loadTimeoutMs: 30_000, requestsEveryMs: 60_000, staleAfterMs: 600_000, restartEveryMs: 3_600_000, memSoftMB: 100_000, memHardMB: 100_000,
    };
    const bot = new LiveInbox(cfg);
    try {
      await bot.start();
      expect(bot.status).toBe("live");
      // The sticker from the first load arrives.
      expect(await until(() => posted.length === 1)).toBe(true);
      expect(posted[0]).toMatchObject({ type: "sticker", sender: { id: MIA } });
      // Images are never downloaded.
      expect(requested.some((u) => u.includes("big-image"))).toBe(false);

      // Later, TikTok pushes a new sticker and a code over the WebSocket: no reload needed.
      expect(await until(() => socket !== null)).toBe(true);
      socket!.write(wsFrame(Buffer.from(Uint8Array.from([
        ...stickerMsg("22222222-2222-4222-8222-222222222222", undefined, MIA),
        ...textMsg("33333333-3333-4333-8333-333333333333", "SB-ABCDEF", MIA),
      ]))));
      expect(await until(() => posted.length === 3)).toBe(true);
      expect(posted.map((p) => p.messageId)).toEqual([
        "tt:11111111-1111-4111-8111-111111111111",
        "tt:22222222-2222-4222-8222-222222222222",
        "tt:33333333-3333-4333-8333-333333333333",
      ]);
      expect(bot.counts.frames).toBeGreaterThanOrEqual(1);
      expect(requested.filter((u) => u.startsWith("/messages")).length).toBe(1); // the tab was loaded once
      expect(bot.health()).toMatchObject({ ok: true, status: "live", botId: BOT });
    } finally {
      await bot.stop();
    }
  }, 90_000);
});
