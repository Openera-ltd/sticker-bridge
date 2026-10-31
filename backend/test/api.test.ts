import { env, exports } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import { cleanup, findCode, isAnimated, isWhatsAppAnimated, newCode, sha256Hex, sniffType, toWhatsAppAnimated, webpSize } from "../src/index";

const BASE = "https://api.sticker.openera.tech";
const worker = (exports as unknown as { default: Fetcher }).default;
const call = (path: string, init?: RequestInit) => worker.fetch(new Request(BASE + path, init));
const bot = (msg: object) =>
  call("/internal/inbox", { method: "POST", headers: { authorization: "Bearer bot-secret" }, body: JSON.stringify(msg) });

// A tiny valid PNG (1x1)
const PNG = Uint8Array.from(atob("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="), (c) => c.charCodeAt(0));
const b64 = (u: Uint8Array) => btoa(String.fromCharCode(...u));
let n = 0;
const msgId = () => `m${Date.now()}-${n++}`;

async function connect(senderId = "tt-1", username = "mia.draws", installId = crypto.randomUUID()) {
  const start = await (await call("/v1/link/start", { method: "POST", body: JSON.stringify({ installId }) })).json<any>();
  const status = (code = start.code, poll = start.pollToken) =>
    call(`/v1/link/status?code=${code}`, { headers: { "x-poll-token": poll } });
  const before = await (await status()).json<any>();
  const linked = await (await bot({ messageId: msgId(), sender: { id: senderId, username }, type: "text", text: `hey ${start.code.toLowerCase()} 👋` })).json<any>();
  const after = await (await status()).json<any>();
  return { start, before, linked, after, token: after.token as string, status };
}

describe("connecting a TikTok account", () => {
  it("links the phone when the code arrives from TikTok", async () => {
    const { start, before, linked, after, status } = await connect();
    expect(start.code).toMatch(/^SB-[23456789A-HJ-NP-Z]{6}$/);
    expect(start.expiresAt).toBeGreaterThan(Date.now());
    expect(before.status).toBe("pending");
    expect(linked.action).toBe("linked");
    expect(linked.reply).toContain("Connected");
    expect(after).toMatchObject({ status: "linked", account: { id: "tt-1", username: "mia.draws" } });
    expect(after.token).toMatch(/^[0-9a-f]{64}$/);
    // The token is handed over only once.
    expect((await (await status()).json<any>()).status).toBe("expired");
    // Only its hash is stored.
    const row = await env.DB.prepare("SELECT token_hash FROM devices").first<{ token_hash: string }>();
    expect(row!.token_hash).not.toBe(after.token);
  });

  it("needs the matching poll token", async () => {
    const { start } = await connect();
    const res = await call(`/v1/link/status?code=${start.code}`, { headers: { "x-poll-token": "wrong" } });
    expect(res.status).toBe(404);
  });

  it("rejects unknown, reused and expired codes", async () => {
    const r1 = await (await bot({ messageId: msgId(), sender: { id: "x", username: "x" }, type: "text", text: "SB-ZZZZZZ" })).json<any>();
    expect(r1.action).toBe("bad_code");

    const { start } = await connect("tt-2", "a");
    const reuse = await (await bot({ messageId: msgId(), sender: { id: "tt-3", username: "b" }, type: "text", text: start.code })).json<any>();
    expect(reuse.action).toBe("bad_code");

    const s = await (await call("/v1/link/start", { method: "POST", body: JSON.stringify({ installId: crypto.randomUUID() }) })).json<any>();
    await env.DB.prepare("UPDATE links SET expires_at = 1 WHERE code = ?").bind(s.code).run();
    const late = await (await bot({ messageId: msgId(), sender: { id: "tt-4", username: "c" }, type: "text", text: s.code })).json<any>();
    expect(late.action).toBe("bad_code");
  });

  it("ignores chat that isn't a code", async () => {
    const r = await (await bot({ messageId: msgId(), sender: { id: "x", username: "x" }, type: "text", text: "hello!" })).json<any>();
    expect(r.action).toBe("ignored");
  });

  it("limits how many codes one install can ask for", async () => {
    const installId = crypto.randomUUID();
    const codes = [];
    for (let i = 0; i < 7; i++) codes.push((await call("/v1/link/start", { method: "POST", body: JSON.stringify({ installId }) })).status);
    expect(codes.slice(0, 6).every((c) => c === 200)).toBe(true);
    expect(codes[6]).toBe(429);
    expect((await call("/v1/link/start", { method: "POST", body: "{}" })).status).toBe(400);
  });
});

describe("stickers", () => {
  it("delivers stickers to the connected phone, paged by cursor", async () => {
    const { token } = await connect("tt-10", "sam");
    const auth = { authorization: `Bearer ${token}` };
    for (let i = 0; i < 3; i++) {
      const r = await (await bot({ messageId: msgId(), sender: { id: "tt-10", username: "sam" }, type: "sticker", data: b64(PNG) })).json<any>();
      expect(r.action).toBe("stored");
      expect(r.reply).toContain("pack");
    }
    const page = await (await call("/v1/stickers", { headers: auth })).json<any>();
    expect(page.items).toHaveLength(3);
    expect(page.items[0]).toMatchObject({ animated: false, emojis: [] });
    expect(page.items[0].file).toMatch(/^v1\/stickers\/[0-9a-f]+\/file$/);

    const file = await call("/" + page.items[0].file, { headers: auth });
    expect(file.headers.get("content-type")).toBe("image/png");
    expect(new Uint8Array(await file.arrayBuffer())).toEqual(PNG);

    const next = await (await call(`/v1/stickers?since=${page.cursor}`, { headers: auth })).json<any>();
    expect(next.items).toHaveLength(0);
    expect(next.cursor).toBe(page.cursor);
  });

  it("keeps stickers sent before connecting", async () => {
    const early = await (await bot({ messageId: msgId(), sender: { id: "tt-20", username: "early" }, type: "sticker", data: b64(PNG) })).json<any>();
    expect(early.reply).toContain("connect");
    const { token } = await connect("tt-20", "early");
    const page = await (await call("/v1/stickers", { headers: { authorization: `Bearer ${token}` } })).json<any>();
    expect(page.items).toHaveLength(1);
  });

  it("never shows one account's stickers to another", async () => {
    const a = await connect("tt-30", "a");
    const b = await connect("tt-31", "b");
    await bot({ messageId: msgId(), sender: { id: "tt-30", username: "a" }, type: "sticker", data: b64(PNG) });
    const aItems = (await (await call("/v1/stickers", { headers: { authorization: `Bearer ${a.token}` } })).json<any>()).items;
    const bItems = (await (await call("/v1/stickers", { headers: { authorization: `Bearer ${b.token}` } })).json<any>()).items;
    expect(aItems).toHaveLength(1);
    expect(bItems).toHaveLength(0);
    const steal = await call("/" + aItems[0].file, { headers: { authorization: `Bearer ${b.token}` } });
    expect(steal.status).toBe(404);
  });

  it("requires a valid phone token", async () => {
    expect((await call("/v1/stickers")).status).toBe(401);
    expect((await call("/v1/stickers", { headers: { authorization: "Bearer nope" } })).status).toBe(401);
  });

  it("handles each bot message once and rejects junk", async () => {
    const id = msgId();
    const m = { messageId: id, sender: { id: "tt-40", username: "d" }, type: "sticker", data: b64(PNG) };
    expect((await (await bot(m)).json<any>()).action).toBe("stored");
    expect((await (await bot(m)).json<any>()).action).toBe("duplicate");
    expect((await bot({ messageId: msgId(), sender: { id: "tt-40", username: "d" }, type: "sticker", data: btoa("not an image") })).status).toBe(415);
    expect((await bot({ messageId: msgId(), sender: { id: "tt-40", username: "d" }, type: "sticker", url: "http://insecure/x.png" })).status).toBe(400);
  });

  it("only the bot can post messages", async () => {
    const res = await call("/internal/inbox", { method: "POST", headers: { authorization: "Bearer guess" }, body: "{}" });
    expect(res.status).toBe(401);
  });

  it("disconnect and delete-my-data", async () => {
    const one = await connect("tt-50", "e");
    await bot({ messageId: msgId(), sender: { id: "tt-50", username: "e" }, type: "sticker", data: b64(PNG) });
    expect((await call("/v1/link", { method: "DELETE", headers: { authorization: `Bearer ${one.token}` } })).status).toBe(200);
    expect((await call("/v1/stickers", { headers: { authorization: `Bearer ${one.token}` } })).status).toBe(401);

    const two = await connect("tt-50", "e");
    const before = await env.DB.prepare("SELECT r2_key FROM stickers WHERE account_id = 'tt-50'").all<{ r2_key: string }>();
    expect(before.results.length).toBe(1);
    expect((await call("/v1/me", { method: "DELETE", headers: { authorization: `Bearer ${two.token}` } })).status).toBe(200);
    expect(await env.FILES.get(before.results[0].r2_key)).toBeNull();
    const left = await env.DB.prepare("SELECT COUNT(*) AS n FROM stickers WHERE account_id = 'tt-50'").first<{ n: number }>();
    expect(left!.n).toBe(0);
  });

  it("daily clean-up removes stickers older than 30 days", async () => {
    await bot({ messageId: msgId(), sender: { id: "tt-60", username: "f" }, type: "sticker", data: b64(PNG) });
    const row = await env.DB.prepare("SELECT r2_key FROM stickers WHERE account_id = 'tt-60'").first<{ r2_key: string }>();
    await cleanup(env as any, Date.now() + 31 * 24 * 60 * 60_000);
    expect(await env.DB.prepare("SELECT 1 FROM stickers WHERE account_id = 'tt-60'").first()).toBeNull();
    expect(await env.FILES.get(row!.r2_key)).toBeNull();
  });
});

describe("app updates and config", () => {
  it("publishes a release that the app can find, download and verify", async () => {
    expect((await call("/v1/app/latest")).status).toBe(404);
    const apk = new Uint8Array(4096).fill(7);
    apk[0] = 0x50; apk[1] = 0x4b;
    const denied = await call("/admin/releases/2?name=1.1.0", { method: "PUT", body: apk });
    expect(denied.status).toBe(401);
    const pub = await (await call("/admin/releases/2?name=1.1.0&notes=Faster%20sync", { method: "PUT", headers: { authorization: "Bearer admin-secret" }, body: apk })).json<any>();
    expect(pub.sha256).toBe(await sha256Hex(apk));

    const latest = await (await call("/v1/app/latest")).json<any>();
    expect(latest).toEqual({ versionCode: 2, versionName: "1.1.0", apk: "v1/app/apk/2", sha256: pub.sha256, size: 4096, notes: "Faster sync", mandatory: false });

    const pub2 = await call("/download");
    expect(await sha256Hex(await pub2.arrayBuffer())).toBe(latest.sha256);
    const dl = await call("/" + latest.apk);
    expect(dl.headers.get("content-type")).toBe("application/vnd.android.package-archive");
    expect(await sha256Hex(await dl.arrayBuffer())).toBe(latest.sha256);
  });

  it("serves config the admin can change", async () => {
    expect(await (await call("/v1/config")).json()).toEqual({ minVersionCode: 0, notice: null, botUsername: "stickerbridge" });
    await call("/admin/config", { method: "PUT", headers: { authorization: "Bearer admin-secret" }, body: JSON.stringify({ notice: "Back soon", minVersionCode: 2 }) });
    expect(await (await call("/v1/config")).json()).toEqual({ minVersionCode: 2, notice: "Back soon", botUsername: "stickerbridge" });
  });

  it("answers unknown paths with 404", async () => {
    expect((await call("/nope")).status).toBe(404);
    expect((await call("/")).status).toBe(200);
  });
});

describe("bot login storage", () => {
  it("saves and returns the bot's login, bot only", async () => {
    const h = { authorization: "Bearer bot-secret" };
    expect((await call("/internal/bot-state", { headers: h })).status).toBe(404);
    expect((await call("/internal/bot-state", { method: "PUT", headers: h, body: "not json" })).status).toBe(400);
    const state = { cookies: [{ name: "sessionid", value: "abc", domain: ".tiktok.com", path: "/" }] };
    expect((await call("/internal/bot-state", { method: "PUT", headers: h, body: JSON.stringify(state) })).status).toBe(200);
    expect(await (await call("/internal/bot-state", { headers: h })).json()).toEqual(state);
    // A logged-out browser's cookies never replace the working login.
    const loggedOut = { cookies: [{ name: "msToken", value: "x", domain: ".tiktok.com", path: "/" }] };
    expect((await call("/internal/bot-state", { method: "PUT", headers: h, body: JSON.stringify(loggedOut) })).status).toBe(422);
    expect(await (await call("/internal/bot-state", { headers: h })).json()).toEqual(state);
    expect((await call("/internal/bot-state", { headers: { authorization: "Bearer admin-secret" } })).status).toBe(401);
  });
});

/** A fake animated WebP header with a given canvas size and total length. */
function animWebp(w: number, h: number, length = 64): Uint8Array {
  const b = new Uint8Array(length);
  b.set([..."RIFF"].map((c) => c.charCodeAt(0)), 0);
  b.set([..."WEBPVP8X"].map((c) => c.charCodeAt(0)), 8);
  b[20] = 0x02;
  const put = (o: number, v: number) => b.set([v & 255, (v >> 8) & 255, (v >> 16) & 255], o);
  put(24, w - 1); put(27, h - 1);
  return b;
}

describe("animated sticker conversion", () => {
  it("knows WhatsApp's animated format", () => {
    expect(webpSize(animWebp(512, 512))).toEqual([512, 512]);
    expect(isWhatsAppAnimated(animWebp(512, 512))).toBe(true);
    expect(isWhatsAppAnimated(animWebp(480, 512))).toBe(false);
    expect(isWhatsAppAnimated(animWebp(512, 512, 600 * 1024))).toBe(false);
  });

  it("asks Cloudflare for 512x512 animated WebP and lowers quality until it fits", async () => {
    const asked: any[] = [];
    const fake = (async (_url: string, init: any) => {
      asked.push(init.cf.image);
      const tooBig = init.cf.image.quality > 42;
      return new Response(animWebp(512, 512, tooBig ? 700 * 1024 : 300 * 1024));
    }) as unknown as typeof fetch;
    const out = await toWhatsAppAnimated("https://example.com/s.awebp", fake);
    expect(out!.byteLength).toBe(300 * 1024);
    expect(asked.map((a) => a.quality)).toEqual([80, 60, 42]);
    expect(asked[0]).toMatchObject({ width: 512, height: 512, fit: "pad", format: "webp", anim: true });
  });

  it("gives up when the result isn't animated or never fits", async () => {
    const still = (async () => new Response(new Uint8Array(100))) as unknown as typeof fetch;
    expect(await toWhatsAppAnimated("https://x", still)).toBeNull();
    const huge = (async () => new Response(animWebp(512, 512, 900 * 1024))) as unknown as typeof fetch;
    expect(await toWhatsAppAnimated("https://x", huge)).toBeNull();
  });
});

describe("helpers", () => {
  it("finds codes however people type them", () => {
    expect(findCode("SB-7KQ4MZ")).toBe("SB-7KQ4MZ");
    expect(findCode("my code is sb 7kq4mz thanks")).toBe("SB-7KQ4MZ");
    expect(findCode("SB7KQ4MZ")).toBe("SB-7KQ4MZ");
    expect(findCode("SB-12345")).toBeNull();
    expect(findCode("hello")).toBeNull();
    for (let i = 0; i < 50; i++) expect(findCode(newCode())).not.toBeNull();
  });

  it("recognises image types and animation", () => {
    expect(sniffType(PNG)).toBe("image/png");
    const webp = new Uint8Array(30);
    webp.set([..."RIFF"].map((c) => c.charCodeAt(0)), 0);
    webp.set([..."WEBPVP8X"].map((c) => c.charCodeAt(0)), 8);
    expect(sniffType(webp)).toBe("image/webp");
    expect(isAnimated(webp)).toBe(false);
    webp[20] = 0x02;
    expect(isAnimated(webp)).toBe(true);
    expect(sniffType(new TextEncoder().encode("hello"))).toBeNull();
  });
});

describe("usernames from user ids", () => {
  it("reads the @username from TikTok's share redirect", async () => {
    const { usernameFromId, isPlaceholderName } = await import("../src/index");
    const fake = (async (u: string) => {
      expect(u).toBe("https://www.tiktok.com/share/user/7579924027591345174");
      return new Response(null, { status: 301, headers: { location: "https://www.tiktok.com/@togata_ps5?_r=1" } });
    }) as unknown as typeof fetch;
    expect(await usernameFromId("7579924027591345174", fake)).toBe("togata_ps5");
    expect(await usernameFromId("not-an-id", fake)).toBeNull();
    const down = (async () => { throw new Error("offline"); }) as unknown as typeof fetch;
    expect(await usernameFromId("7579924027591345174", down)).toBeNull();
    expect(isPlaceholderName("u_7579924027591345174", "7579924027591345174")).toBe(true);
    expect(isPlaceholderName("togata_ps5", "7579924027591345174")).toBe(false);
  });
});
