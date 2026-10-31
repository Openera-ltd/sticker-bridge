/**
 * Sticker Bridge API — the one server the Android app talks to (api.sticker.openera.tech).
 * Contract: ../API.md. Storage: D1 (rows) + R2 (sticker and APK files).
 */

export interface Env {
  DB: D1Database;
  FILES: R2Bucket;
  BOT_USERNAME: string;
  /** The @stickerbridge bot sends messages here. */
  BOT_SECRET: string;
  /** For publishing app updates and changing config. */
  ADMIN_SECRET: string;
  /** The live bot on Render; pinged so the free instance never sleeps. */
  BOT_URL?: string;
}

const CODE_TTL_MS = 10 * 60_000;
const STICKER_TTL_MS = 30 * 24 * 60 * 60_000;
const MAX_STICKER_BYTES = 5 * 1024 * 1024;
const MAX_CODES_PER_HOUR = 6;
const PAGE = 50;
export const KEEP_AWAKE_CRON = "*/10 * * * *";
const CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"; // no 0/O/1/I/L

// ---------- small helpers ----------

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });

const error = (status: number, message: string) => json({ error: message }, status);

function randomHex(bytes: number): string {
  const b = crypto.getRandomValues(new Uint8Array(bytes));
  return [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
}

export function newCode(): string {
  const b = crypto.getRandomValues(new Uint8Array(6));
  return "SB-" + [...b].map((x) => CODE_ALPHABET[x % CODE_ALPHABET.length]).join("");
}

export async function sha256Hex(data: ArrayBuffer | Uint8Array | string): Promise<string> {
  const buf = typeof data === "string" ? new TextEncoder().encode(data) : data;
  const d = await crypto.subtle.digest("SHA-256", buf);
  return [...new Uint8Array(d)].map((x) => x.toString(16).padStart(2, "0")).join("");
}

/** Compares secrets without leaking how much matched. */
async function sameSecret(a: string, b: string): Promise<boolean> {
  if (!a || !b) return false;
  const [x, y] = await Promise.all([sha256Hex(a), sha256Hex(b)]);
  let diff = 0;
  for (let i = 0; i < x.length; i++) diff |= x.charCodeAt(i) ^ y.charCodeAt(i);
  return diff === 0;
}

function bearer(req: Request): string | null {
  const h = req.headers.get("authorization") ?? "";
  return h.startsWith("Bearer ") ? h.slice(7).trim() : null;
}

/** Finds a code like "SB-7KQ4MZ" anywhere in a message, forgiving spaces and case. */
export function findCode(text: string): string | null {
  const m = text.toUpperCase().match(/SB\s*-?\s*([23456789A-HJ-NP-Z]{6})/);
  return m ? "SB-" + m[1] : null;
}

function cleanId(id: string): string {
  return id.replace(/[^A-Za-z0-9_-]/g, "").slice(0, 64);
}

async function device(env: Env, req: Request): Promise<{ accountId: string; tokenHash: string } | null> {
  const token = bearer(req);
  if (!token) return null;
  const tokenHash = await sha256Hex(token);
  const row = await env.DB.prepare("SELECT account_id FROM devices WHERE token_hash = ?").bind(tokenHash).first<{ account_id: string }>();
  if (!row) return null;
  await env.DB.prepare("UPDATE devices SET last_seen = ? WHERE token_hash = ?").bind(Date.now(), tokenHash).run();
  return { accountId: row.account_id, tokenHash };
}

// ---------- app endpoints ----------

async function linkStart(env: Env, req: Request): Promise<Response> {
  const body = await req.json<{ installId?: string }>().catch(() => ({}) as { installId?: string });
  const installId = (body.installId ?? "").slice(0, 64);
  if (!/^[A-Za-z0-9-]{8,64}$/.test(installId)) return error(400, "installId required");
  const now = Date.now();
  const recent = await env.DB.prepare("SELECT COUNT(*) AS n FROM links WHERE install_id = ? AND created_at > ?")
    .bind(installId, now - 60 * 60_000).first<{ n: number }>();
  if ((recent?.n ?? 0) >= MAX_CODES_PER_HOUR) return error(429, "Too many codes. Try again later.");
  const pollToken = randomHex(24);
  for (let attempt = 0; attempt < 5; attempt++) {
    const code = newCode();
    try {
      await env.DB.prepare("INSERT INTO links (code, poll_hash, install_id, created_at, expires_at) VALUES (?, ?, ?, ?, ?)")
        .bind(code, await sha256Hex(pollToken), installId, now, now + CODE_TTL_MS).run();
      return json({ code, pollToken, expiresAt: now + CODE_TTL_MS });
    } catch {
      // code collision: try another
    }
  }
  return error(503, "Try again");
}

async function linkStatus(env: Env, req: Request, url: URL): Promise<Response> {
  const code = url.searchParams.get("code") ?? "";
  const poll = req.headers.get("x-poll-token") ?? "";
  const row = await env.DB.prepare("SELECT * FROM links WHERE code = ?").bind(code).first<{
    poll_hash: string; status: string; account_id: string | null; token: string | null; expires_at: number;
  }>();
  if (!row || row.poll_hash !== (await sha256Hex(poll))) return error(404, "Unknown code");
  if (row.status === "linked" && row.token && row.account_id) {
    const acc = await env.DB.prepare("SELECT id, username FROM accounts WHERE id = ?").bind(row.account_id).first<{ id: string; username: string }>();
    // Hand the token over once, then forget it.
    await env.DB.prepare("UPDATE links SET status = 'delivered', token = NULL WHERE code = ?").bind(code).run();
    return json({ status: "linked", token: row.token, account: acc });
  }
  if (row.status === "delivered" || Date.now() > row.expires_at) return json({ status: "expired" });
  return json({ status: "pending" });
}

async function stickersList(env: Env, req: Request, url: URL): Promise<Response> {
  const d = await device(env, req);
  if (!d) return error(401, "Not connected");
  const since = Number.parseInt(url.searchParams.get("since") ?? "0", 10) || 0;
  const { results } = await env.DB.prepare(
    "SELECT seq, id, animated, emojis, created_at, replaces FROM stickers WHERE account_id = ? AND seq > ? ORDER BY seq LIMIT ?",
  ).bind(d.accountId, since, PAGE).all<{ seq: number; id: string; animated: number; emojis: string; created_at: number; replaces: string | null }>();
  const items = results.map((r) => ({
    id: r.id,
    file: `v1/stickers/${r.id}/file`,
    animated: r.animated === 1,
    createdAt: r.created_at,
    emojis: JSON.parse(r.emojis) as string[],
    ...(r.replaces ? { replaces: r.replaces } : {}),
  }));
  const cursor = results.length ? String(results[results.length - 1].seq) : String(since);
  return json({ items, cursor });
}

async function stickerFile(env: Env, req: Request, id: string): Promise<Response> {
  const d = await device(env, req);
  if (!d) return error(401, "Not connected");
  const row = await env.DB.prepare("SELECT r2_key, content_type FROM stickers WHERE id = ? AND account_id = ?")
    .bind(id, d.accountId).first<{ r2_key: string; content_type: string }>();
  if (!row) return error(404, "No such sticker");
  const obj = await env.FILES.get(row.r2_key);
  if (!obj) return error(404, "File gone");
  return new Response(obj.body, { headers: { "content-type": row.content_type, "cache-control": "private, max-age=86400" } });
}

async function unlink(env: Env, req: Request): Promise<Response> {
  const d = await device(env, req);
  if (!d) return error(401, "Not connected");
  await env.DB.prepare("DELETE FROM devices WHERE token_hash = ?").bind(d.tokenHash).run();
  return json({ ok: true });
}

async function deleteMe(env: Env, req: Request): Promise<Response> {
  const d = await device(env, req);
  if (!d) return error(401, "Not connected");
  await deleteStickers(env, "SELECT r2_key FROM stickers WHERE account_id = ?", [d.accountId]);
  await env.DB.batch([
    env.DB.prepare("DELETE FROM stickers WHERE account_id = ?").bind(d.accountId),
    env.DB.prepare("DELETE FROM devices WHERE account_id = ?").bind(d.accountId),
    env.DB.prepare("DELETE FROM accounts WHERE id = ?").bind(d.accountId),
  ]);
  return json({ ok: true });
}

async function deleteStickers(env: Env, select: string, args: unknown[]) {
  const { results } = await env.DB.prepare(select).bind(...args).all<{ r2_key: string }>();
  for (let i = 0; i < results.length; i += 500) {
    await env.FILES.delete(results.slice(i, i + 500).map((r) => r.r2_key));
  }
}

async function config(env: Env): Promise<Response> {
  const { results } = await env.DB.prepare("SELECT key, value FROM config").all<{ key: string; value: string }>();
  const c = Object.fromEntries(results.map((r) => [r.key, r.value]));
  return json({
    minVersionCode: Number(c.minVersionCode ?? 0),
    notice: c.notice || null,
    botUsername: env.BOT_USERNAME,
  });
}

type Release = { version_code: number; version_name: string; r2_key: string; sha256: string; size: number; notes: string; mandatory: number };

async function latestApp(env: Env): Promise<Response> {
  const r = await env.DB.prepare("SELECT * FROM releases ORDER BY version_code DESC LIMIT 1").first<Release>();
  if (!r) return error(404, "No releases yet");
  return json({
    versionCode: r.version_code,
    versionName: r.version_name,
    apk: `v1/app/apk/${r.version_code}`,
    sha256: r.sha256,
    size: r.size,
    notes: r.notes,
    mandatory: r.mandatory === 1,
  });
}

async function apkFile(env: Env, code: string): Promise<Response> {
  const r = await env.DB.prepare("SELECT * FROM releases WHERE version_code = ?").bind(Number(code)).first<Release>();
  const obj = r ? await env.FILES.get(r.r2_key) : null;
  if (!r || !obj) return error(404, "No such release");
  return new Response(obj.body, {
    headers: {
      "content-type": "application/vnd.android.package-archive",
      "content-length": String(r.size),
      "content-disposition": `attachment; filename="StickerBridge-${r.version_name}.apk"`,
      "cache-control": "public, max-age=31536000, immutable",
    },
  });
}

// ---------- bot endpoint ----------

export interface InboxMessage {
  /** TikTok's message id; used so a retried message is only handled once. */
  messageId: string;
  sender: { id: string; username: string };
  type: "text" | "sticker";
  text?: string;
  /** Sticker image: either a URL the bot saw, or the bytes as base64. */
  url?: string;
  data?: string;
  contentType?: string;
  animated?: boolean;
}

async function inbox(env: Env, req: Request): Promise<Response> {
  if (!(await sameSecret(bearer(req) ?? "", env.BOT_SECRET))) return error(401, "Bot only");
  const m = await req.json<InboxMessage>().catch(() => null);
  const senderId = cleanId(m?.sender?.id ?? "");
  if (!m || !m.messageId || !senderId) return error(400, "Bad message");
  const username = (m.sender.username ?? "").replace(/^@/, "").slice(0, 64) || senderId;
  const now = Date.now();

  const seen = await env.DB.prepare("INSERT OR IGNORE INTO processed (message_id, created_at) VALUES (?, ?)")
    .bind(m.messageId.slice(0, 128), now).run();
  if (seen.meta.changes === 0) return json({ action: "duplicate" });

  await env.DB.prepare(
    "INSERT INTO accounts (id, username, created_at) VALUES (?, ?, ?) ON CONFLICT(id) DO UPDATE SET username = excluded.username",
  ).bind(senderId, username, now).run();

  if (m.type === "text") {
    const code = findCode(m.text ?? "");
    if (!code) return json({ action: "ignored", reply: null });
    const link = await env.DB.prepare("SELECT status, install_id, expires_at FROM links WHERE code = ?").bind(code)
      .first<{ status: string; install_id: string; expires_at: number }>();
    if (!link || link.status !== "pending" || now > link.expires_at) {
      return json({ action: "bad_code", reply: "That code didn't work. Open Sticker Bridge and get a new one." });
    }
    const token = randomHex(32);
    await env.DB.batch([
      env.DB.prepare("INSERT INTO devices (token_hash, account_id, install_id, created_at, last_seen) VALUES (?, ?, ?, ?, ?)")
        .bind(await sha256Hex(token), senderId, link.install_id, now, now),
      env.DB.prepare("UPDATE links SET status = 'linked', account_id = ?, token = ? WHERE code = ? AND status = 'pending'")
        .bind(senderId, token, code),
    ]);
    return json({ action: "linked", reply: "Connected ✅ Now send me any sticker and it'll show up in Sticker Bridge." });
  }

  if (m.type === "sticker") {
    let bytes: ArrayBuffer;
    let contentType = m.contentType ?? "";
    if (m.data) {
      bytes = Uint8Array.from(atob(m.data), (c) => c.charCodeAt(0)).buffer;
    } else if (m.url && m.url.startsWith("https://")) {
      const res = await fetch(m.url, { headers: { "user-agent": "Mozilla/5.0" } });
      if (!res.ok) return error(502, `Couldn't fetch sticker (${res.status})`);
      bytes = await res.arrayBuffer();
      contentType ||= res.headers.get("content-type") ?? "";
    } else {
      return error(400, "Sticker needs data or an https url");
    }
    if (bytes.byteLength === 0 || bytes.byteLength > MAX_STICKER_BYTES) return error(413, "Sticker too large");
    contentType = sniffType(new Uint8Array(bytes)) ?? contentType;
    if (!contentType.startsWith("image/")) return error(415, "Not an image");

    // Animated stickers: Cloudflare re-makes them in WhatsApp's animated format. If that isn't possible,
    // the original is kept and the app uses its first frame as a still sticker.
    let animated = m.animated === true || isAnimated(new Uint8Array(bytes));
    if (animated && m.url) {
      const converted = await toWhatsAppAnimated(m.url, fetch, r2Stage(env)).catch(() => null);
      if (converted) {
        bytes = converted;
        contentType = "image/webp";
      }
    }
    animated = isAnimated(new Uint8Array(bytes)) && isWhatsAppAnimated(new Uint8Array(bytes));

    const id = randomHex(12);
    const key = `stickers/${senderId}/${id}`;
    await env.FILES.put(key, bytes, { httpMetadata: { contentType } });
    await env.DB.prepare(
      "INSERT INTO stickers (id, account_id, r2_key, content_type, animated, size, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
    ).bind(id, senderId, key, contentType, animated ? 1 : 0, bytes.byteLength, now).run();

    const connected = await env.DB.prepare("SELECT 1 FROM devices WHERE account_id = ? LIMIT 1").bind(senderId).first();
    return json({
      action: "stored",
      id,
      reply: connected
        ? "Got it ✅ It's in your Sticker Bridge pack."
        : "Got it ✅ Open Sticker Bridge and connect your account to add it to WhatsApp.",
    });
  }
  return error(400, "Unknown message type");
}

const MAX_ANIMATED_BYTES = 500 * 1024;
const PUBLIC_BASE = "https://api.sticker.openera.tech";

/** WebP canvas size from its VP8X header. */
export function webpSize(b: Uint8Array): [number, number] | null {
  const s = (o: number, n: number) => String.fromCharCode(...b.slice(o, o + n));
  if (b.length < 30 || s(0, 4) !== "RIFF" || s(12, 4) !== "VP8X") return null;
  const u24 = (o: number) => b[o] | (b[o + 1] << 8) | (b[o + 2] << 16);
  return [u24(24) + 1, u24(27) + 1];
}

/** What WhatsApp accepts as an animated sticker: animated WebP, 512 x 512, at most 500 KB. */
export function isWhatsAppAnimated(b: Uint8Array): boolean {
  const size = webpSize(b);
  return b.length <= MAX_ANIMATED_BYTES && size !== null && size[0] === 512 && size[1] === 512;
}

/**
 * Uses Cloudflare Image Transformations to turn an animated image into a 512 x 512 animated WebP
 * (transparent padding) under 500 KB, lowering quality until it fits. Null if it can't.
 */
/** Saves bytes somewhere Cloudflare Images can read them; returns the URL and a clean-up. */
export type Stage = (bytes: ArrayBuffer) => Promise<{ url: string; done: () => Promise<void> }>;

export async function toWhatsAppAnimated(url: string, fetcher: typeof fetch = fetch, stage?: Stage): Promise<ArrayBuffer | null> {
  const transform = async (src: string, image: Record<string, unknown>) => {
    const res = await fetcher(src, { headers: { "user-agent": "Mozilla/5.0" }, cf: { image } } as RequestInit);
    return res.ok ? new Uint8Array(await res.arrayBuffer()) : null;
  };
  const pad = (quality: number) => ({ width: 512, height: 512, fit: "pad", background: "rgba(0,0,0,0)", format: "webp", anim: true, quality });

  // 1. Full size, lowering quality until it fits.
  for (const quality of [80, 60, 42, 28, 20, 15]) {
    const b = await transform(url, pad(quality));
    if (!b) return null;
    if (!isAnimated(b)) return null; // came back still: not worth keeping over the original
    if (isWhatsAppAnimated(b)) return b.buffer as ArrayBuffer;
  }
  // 2. Big animations: shrink the picture first, then pad it back to 512 x 512 (transparent edges cost
  //    almost nothing), so it fits WhatsApp's 500 KB limit and still moves.
  if (!stage) return null;
  for (const size of [320, 256, 192]) {
    const small = await transform(url, { width: size, height: size, fit: "contain", format: "webp", anim: true, quality: 30 });
    if (!small || !isAnimated(small)) return null;
    const staged = await stage(small.buffer as ArrayBuffer);
    try {
      for (const quality of [30, 15]) {
        const b = await transform(staged.url, pad(quality));
        if (b && isAnimated(b) && isWhatsAppAnimated(b)) return b.buffer as ArrayBuffer;
      }
    } finally {
      await staged.done().catch(() => {});
    }
  }
  return null;
}

/** Stages through R2 and this API's own /v1/tmp route (random, unlisted, deleted right after). */
export function r2Stage(env: Env): Stage {
  return async (bytes) => {
    const id = randomHex(16);
    await env.FILES.put(`tmp/${id}`, bytes, { httpMetadata: { contentType: "image/webp" } });
    return { url: `${PUBLIC_BASE}/v1/tmp/${id}`, done: () => env.FILES.delete(`tmp/${id}`) };
  };
}

export function sniffType(b: Uint8Array): string | null {
  const s = (o: number, n: number) => String.fromCharCode(...b.slice(o, o + n));
  if (b.length >= 12 && s(0, 4) === "RIFF" && s(8, 4) === "WEBP") return "image/webp";
  if (b.length >= 8 && b[0] === 0x89 && s(1, 3) === "PNG") return "image/png";
  if (b.length >= 3 && b[0] === 0xff && b[1] === 0xd8) return "image/jpeg";
  if (b.length >= 6 && s(0, 4) === "GIF8") return "image/gif";
  return null;
}

export function isAnimated(b: Uint8Array): boolean {
  const s = (o: number, n: number) => String.fromCharCode(...b.slice(o, o + n));
  if (s(0, 4) === "RIFF" && s(12, 4) === "VP8X") return (b[20] & 0x02) !== 0;
  if (s(0, 4) === "GIF8") {
    // more than one image descriptor = animated
    let frames = 0;
    for (let i = 0; i < b.length - 1 && frames < 2; i++) if (b[i] === 0x00 && b[i + 1] === 0x2c) frames++;
    return frames > 1;
  }
  return false;
}

/** The bot keeps its TikTok login here and refreshes it after every run. */
async function botState(env: Env, req: Request): Promise<Response> {
  if (!(await sameSecret(bearer(req) ?? "", env.BOT_SECRET))) return error(401, "Bot only");
  // Kept in the database, never in file storage: the bucket may be publicly readable.
  if (req.method === "GET") {
    const row = await env.DB.prepare("SELECT value FROM bot_state WHERE id = 1").first<{ value: string }>();
    return row ? new Response(row.value, { headers: { "content-type": "application/json" } }) : error(404, "No saved state");
  }
  const body = await req.text();
  if (body.length > 200_000) return error(413, "Too large");
  let parsed: { cookies?: { name?: string; value?: string }[] };
  try {
    parsed = JSON.parse(body);
  } catch {
    return error(400, "JSON body required");
  }
  // Never replace a working login with a logged-out one (no TikTok session cookie).
  if (!parsed.cookies?.some((c) => c.name === "sessionid" && c.value)) return error(422, "No TikTok session in these cookies");
  await env.DB.prepare("INSERT OR REPLACE INTO bot_state (id, value, updated_at) VALUES (1, ?, ?)").bind(body, Date.now()).run();
  return json({ ok: true });
}

/**
 * Re-makes stored stickers that are animated but were kept as stills (too big for WhatsApp before the
 * shrink step existed). Each fixed one gets a new id that "replaces" the old, so phones swap it.
 * A few per call (Workers limit subrequests); call again until "left" is 0.
 */
async function reconvert(env: Env, req: Request): Promise<Response> {
  if (!(await sameSecret(bearer(req) ?? "", env.ADMIN_SECRET))) return error(401, "Admin only");
  const { limit = 2, after = 0 } = await req.json<{ limit?: number; after?: number }>().catch(() => ({}) as { limit?: number; after?: number });
  const { results } = await env.DB.prepare(
    "SELECT seq, id, account_id, r2_key, created_at FROM stickers WHERE animated = 0 AND seq > ? ORDER BY seq LIMIT ?",
  ).bind(after, Math.min(limit, 3)).all<{ seq: number; id: string; account_id: string; r2_key: string; created_at: number }>();
  const done: { id: string; result: string }[] = [];
  let last = after;
  for (const row of results) {
    last = row.seq;
    const obj = await env.FILES.get(row.r2_key);
    if (!obj) { done.push({ id: row.id, result: "missing" }); continue; }
    const original = await obj.arrayBuffer();
    if (!isAnimated(new Uint8Array(original))) { done.push({ id: row.id, result: "still" }); continue; }
    const staged = await r2Stage(env)(original);
    let out: ArrayBuffer | null = null;
    try {
      out = await toWhatsAppAnimated(staged.url, fetch, r2Stage(env)).catch(() => null);
    } finally {
      await staged.done();
    }
    if (!out) { done.push({ id: row.id, result: "too big" }); continue; }
    const id = randomHex(12);
    const key = `stickers/${row.account_id}/${id}`;
    await env.FILES.put(key, out, { httpMetadata: { contentType: "image/webp" } });
    await env.DB.batch([
      env.DB.prepare(
        "INSERT INTO stickers (id, account_id, r2_key, content_type, animated, size, created_at, replaces) VALUES (?, ?, ?, 'image/webp', 1, ?, ?, ?)",
      ).bind(id, row.account_id, key, out.byteLength, row.created_at, row.id),
      env.DB.prepare("DELETE FROM stickers WHERE id = ?").bind(row.id),
    ]);
    await env.FILES.delete(row.r2_key);
    done.push({ id: row.id, result: `animated ${out.byteLength}` });
  }
  const left = await env.DB.prepare("SELECT COUNT(*) AS n FROM stickers WHERE animated = 0 AND seq > ?").bind(last).first<{ n: number }>();
  return json({ done, next: last, left: left?.n ?? 0 });
}

// ---------- admin ----------

async function publishRelease(env: Env, req: Request, code: string, url: URL): Promise<Response> {
  if (!(await sameSecret(bearer(req) ?? "", env.ADMIN_SECRET))) return error(401, "Admin only");
  const versionCode = Number.parseInt(code, 10);
  const versionName = url.searchParams.get("name") ?? "";
  if (!versionCode || !versionName) return error(400, "Need version code and ?name=");
  const apk = await req.arrayBuffer();
  if (apk.byteLength < 1000 || new Uint8Array(apk)[0] !== 0x50) return error(400, "Body must be an APK");
  const sha = await sha256Hex(apk);
  const key = `apk/${versionCode}.apk`;
  await env.FILES.put(key, apk, { httpMetadata: { contentType: "application/vnd.android.package-archive" } });
  await env.DB.prepare(
    "INSERT OR REPLACE INTO releases (version_code, version_name, r2_key, sha256, size, notes, mandatory, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
  ).bind(versionCode, versionName, key, sha, apk.byteLength, url.searchParams.get("notes") ?? "", url.searchParams.get("mandatory") === "1" ? 1 : 0, Date.now()).run();
  return json({ ok: true, versionCode, versionName, sha256: sha, size: apk.byteLength });
}

async function setConfig(env: Env, req: Request): Promise<Response> {
  if (!(await sameSecret(bearer(req) ?? "", env.ADMIN_SECRET))) return error(401, "Admin only");
  const body = await req.json<Record<string, string | number | null>>().catch(() => null);
  if (!body) return error(400, "JSON body required");
  const stmts = Object.entries(body).map(([k, v]) =>
    v === null
      ? env.DB.prepare("DELETE FROM config WHERE key = ?").bind(k)
      : env.DB.prepare("INSERT OR REPLACE INTO config (key, value) VALUES (?, ?)").bind(k, String(v)),
  );
  if (stmts.length) await env.DB.batch(stmts);
  return config(env);
}

// ---------- housekeeping ----------

export async function cleanup(env: Env, now = Date.now()) {
  const cutoff = now - STICKER_TTL_MS;
  await deleteStickers(env, "SELECT r2_key FROM stickers WHERE created_at < ?", [cutoff]);
  await env.DB.batch([
    env.DB.prepare("DELETE FROM stickers WHERE created_at < ?").bind(cutoff),
    env.DB.prepare("DELETE FROM links WHERE expires_at < ?").bind(now - 24 * 60 * 60_000),
    env.DB.prepare("DELETE FROM processed WHERE created_at < ?").bind(cutoff),
  ]);
}

// ---------- router ----------

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    const p = url.pathname.replace(/\/+$/, "") || "/";
    const m = req.method;
    try {
      if (p === "/" && m === "GET") return json({ name: "Sticker Bridge API", ok: true });
      if (p === "/v1/link/start" && m === "POST") return await linkStart(env, req);
      if (p === "/v1/link/status" && m === "GET") return await linkStatus(env, req, url);
      if (p === "/v1/link" && m === "DELETE") return await unlink(env, req);
      if (p === "/v1/me" && m === "DELETE") return await deleteMe(env, req);
      if (p === "/v1/stickers" && m === "GET") return await stickersList(env, req, url);
      let r = p.match(/^\/v1\/stickers\/([A-Za-z0-9_-]{1,64})\/file$/);
      if (r && m === "GET") return await stickerFile(env, req, r[1]);
      if (p === "/v1/config" && m === "GET") return await config(env);
      if (p === "/v1/donation" && m === "GET") {
        // Signed by the owner offline; the app verifies the signature before showing anything.
        const row = await env.DB.prepare("SELECT payload, signature FROM donation WHERE id = 1").first<{ payload: string; signature: string }>();
        return row ? json(row) : error(404, "Not set");
      }
      r = p.match(/^\/v1\/tmp\/([0-9a-f]{32})$/);
      if (r && m === "GET") {
        // Short-lived files for image conversion; ids are random and deleted right after use.
        const obj = await env.FILES.get(`tmp/${r[1]}`);
        return obj ? new Response(obj.body, { headers: { "content-type": "image/webp", "cache-control": "no-store" } }) : error(404, "Not found");
      }
      if (p === "/v1/app/latest" && m === "GET") return await latestApp(env);
      r = p.match(/^\/v1\/app\/apk\/(\d{1,9})$/);
      if (r && m === "GET") return await apkFile(env, r[1]);
      if (p === "/download" && m === "GET") {
        // Public link for first installs: always the newest APK.
        const r = await env.DB.prepare("SELECT version_code FROM releases ORDER BY version_code DESC LIMIT 1").first<{ version_code: number }>();
        return r ? await apkFile(env, String(r.version_code)) : error(404, "No releases yet");
      }
      if (p === "/internal/inbox" && m === "POST") return await inbox(env, req);
      if (p === "/internal/bot-state" && (m === "GET" || m === "PUT")) return await botState(env, req);
      r = p.match(/^\/admin\/releases\/(\d{1,9})$/);
      if (r && m === "PUT") return await publishRelease(env, req, r[1], url);
      if (p === "/admin/config" && m === "PUT") return await setConfig(env, req);
      if (p === "/admin/reconvert" && m === "POST") return await reconvert(env, req);
      if (p === "/admin/donation" && m === "PUT") {
        if (!(await sameSecret(bearer(req) ?? "", env.ADMIN_SECRET))) return error(401, "Admin only");
        const b = await req.json<{ payload?: string; signature?: string }>().catch(() => null);
        if (!b?.payload || !b.signature) return error(400, "payload and signature required");
        await env.DB.prepare("INSERT OR REPLACE INTO donation (id, payload, signature, updated_at) VALUES (1, ?, ?, ?)")
          .bind(b.payload, b.signature, Date.now()).run();
        return json({ ok: true });
      }
      if (p === "/admin/convert-test" && m === "POST") {
        // Try the animated-sticker conversion on a URL, for checking Cloudflare Images is working.
        if (!(await sameSecret(bearer(req) ?? "", env.ADMIN_SECRET))) return error(401, "Admin only");
        const { url: src, image } = await req.json<{ url: string; image?: Record<string, unknown> }>();
        const raw = await fetch(src, {
          headers: { "user-agent": "Mozilla/5.0" },
          cf: { image: image ?? { width: 512, height: 512, fit: "pad", format: "webp", anim: true, quality: 80 } },
        } as RequestInit);
        const rb = new Uint8Array(await raw.arrayBuffer());
        const out = await toWhatsAppAnimated(src);
        const b = out ? new Uint8Array(out) : null;
        return json({
          firstTry: { status: raw.status, type: raw.headers.get("content-type"), cfResized: raw.headers.get("cf-resized"), bytes: rb.length, size: webpSize(rb), animated: isAnimated(rb), head: String.fromCharCode(...rb.slice(0, 16)) },
          ok: Boolean(b), bytes: b?.length ?? 0, size: b ? webpSize(b) : null, animated: b ? isAnimated(b) : false,
        });
      }
      return error(404, "Not found");
    } catch (e) {
      console.error("request failed", p, e);
      return error(500, "Something went wrong");
    }
  },

  async scheduled(event: ScheduledController, env: Env, ctx: ExecutionContext) {
    if (event.cron === KEEP_AWAKE_CRON) {
      // Render's free plan sleeps after 15 idle minutes; this keeps the live bot awake.
      if (env.BOT_URL) ctx.waitUntil(fetch(env.BOT_URL.replace(/\/+$/, "") + "/healthz").then(() => {}, () => {}));
    } else {
      ctx.waitUntil(cleanup(env));
    }
  },
} satisfies ExportedHandler<Env>;
