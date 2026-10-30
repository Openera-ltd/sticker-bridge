/**
 * TikTok's web inbox downloads messages from im-api.tiktok.com as protobuf.
 * Each message's content is a JSON string inside that binary data, and stickers
 * are only there (the page itself shows "This message type isn't supported").
 * These helpers find those JSON objects without needing TikTok's protobuf schema.
 */

/** Finds every top-level JSON object embedded in binary data. */
export function extractJsonObjects(bytes: Uint8Array): unknown[] {
  const text = new TextDecoder("utf-8", { fatal: false }).decode(bytes);
  const out: unknown[] = [];
  let i = 0;
  while (i < text.length) {
    const start = text.indexOf("{", i);
    if (start < 0) break;
    const end = matchBrace(text, start);
    if (end > start) {
      try {
        out.push(JSON.parse(text.slice(start, end + 1)));
        i = end + 1;
        continue;
      } catch {
        // not JSON: keep scanning after this brace
      }
    }
    i = start + 1;
  }
  return out;
}

/** Index of the brace closing the one at `start`, respecting strings; -1 if none. */
function matchBrace(s: string, start: number): number {
  let depth = 0;
  let inStr = false;
  for (let i = start; i < s.length && i - start < 200_000; i++) {
    const c = s[i];
    if (inStr) {
      if (c === "\\") i++;
      else if (c === '"') inStr = false;
      continue;
    }
    if (c === '"') inStr = true;
    else if (c === "{") depth++;
    else if (c === "}") {
      depth--;
      if (depth === 0) return i;
    }
    if (c.charCodeAt(0) < 0x20 && c !== "\n" && c !== "\r" && c !== "\t") return -1; // binary: not JSON
  }
  return -1;
}

export interface ImMessage {
  /** TikTok's client message id: stable, so each message is handled once. */
  messageId: string;
  /** The other person in the chat (not the bot). */
  senderId: string;
  kind: "sticker" | "text";
  url?: string;
  stickerId?: string;
  text?: string;
}

/** Reads a protobuf length-prefixed string that starts at `at` (the length varint sits just before it). */
function lengthPrefixed(b: Uint8Array, at: number): string | null {
  let len = -1;
  if (at >= 2 && b[at - 2] & 0x80 && !(b[at - 1] & 0x80)) len = (b[at - 2] & 0x7f) | (b[at - 1] << 7);
  else if (at >= 1 && !(b[at - 1] & 0x80)) len = b[at - 1];
  if (len <= 0 || at + len > b.length) return null;
  return new TextDecoder().decode(b.subarray(at, at + len));
}

/**
 * Pulls stickers and text messages out of an im-api response.
 * Messages start with their conversation id ("0:1:<user>:<user>"); the bot is the user
 * present in every conversation, so the sender is the other one.
 */
export function parseImMessages(bytes: Uint8Array, botId?: string): ImMessage[] {
  const s = new TextDecoder("latin1").decode(bytes);
  const convRe = /\d:\d:(\d{6,25}):(\d{6,25})/g;
  const marks = [...s.matchAll(convRe)];
  if (!marks.length) return [];
  let bot = botId;
  if (!bot) {
    // Without the bot's id, it's the user in every conversation; a tie (one chat) means we can't tell.
    const counts = new Map<string, number>();
    for (const m of marks) for (const id of new Set([m[1], m[2]])) counts.set(id, (counts.get(id) ?? 0) + 1);
    const ranked = [...counts.entries()].sort((a, b) => b[1] - a[1]);
    if (ranked.length > 1 && ranked[0][1] === ranked[1][1]) return [];
    bot = ranked[0][0];
  }

  const out: ImMessage[] = [];
  const seen = new Set<string>();
  marks.forEach((m, i) => {
    const start = m.index!;
    const end = i + 1 < marks.length ? marks[i + 1].index! : s.length;
    const chunk = s.slice(start, end);
    const senderId = m[1] === bot ? m[2] : m[1];
    if (senderId === bot) return;
    const id = chunk.match(/\$([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/)?.[1];
    if (!id || seen.has(id)) return;

    // Stickers sent in DMs live on TikTok's DM-sticker image hosts.
    const urlAt = chunk.search(/https:\/\/[a-z0-9.-]*dm-sticker[a-z0-9.-]*\//);
    if (urlAt >= 0) {
      const url = lengthPrefixed(bytes, start + urlAt);
      if (url && /^https:\/\/[^\s"]+$/.test(url)) {
        seen.add(id);
        out.push({ messageId: id, senderId, kind: "sticker", url, stickerId: chunk.match(/sticker_id[^\d]{1,4}(\d{6,20})/)?.[1] });
        return;
      }
    }
    // Text messages carry JSON content like {"aweType":0,"text":"SB-7KQ4MZ"}.
    for (const o of extractJsonObjects(bytes.subarray(start, end))) {
      const text = (o as { text?: unknown }).text;
      if (typeof text === "string" && text.trim()) {
        seen.add(id);
        out.push({ messageId: id, senderId, kind: "text", text: text.slice(0, 500) });
        return;
      }
    }
  });
  return out;
}

/**
 * The logged-in account's user id, from its cookies: multi_sids lists "<uid>:<session>" pairs
 * and sessionid says which session is active.
 */
export function botIdFromCookies(cookies: { name: string; value: string }[]): string | undefined {
  const session = cookies.find((c) => c.name === "sessionid")?.value;
  const multi = cookies.find((c) => c.name === "multi_sids")?.value;
  if (!session || !multi) return undefined;
  for (const pair of decodeURIComponent(multi).split("|")) {
    const [uid, sid] = pair.split(":");
    if (sid === session && /^\d{5,25}$/.test(uid)) return uid;
  }
  return undefined;
}

/** Finds TikTok usernames for user ids in profile responses (any object with a uid and a unique_id). */
export function usernamesFrom(json: unknown, into = new Map<string, string>()): Map<string, string> {
  if (Array.isArray(json)) json.forEach((x) => usernamesFrom(x, into));
  else if (json && typeof json === "object") {
    const o = json as Record<string, unknown>;
    const uid = o.uid ?? o.user_id ?? o.id_str ?? o.uid_str;
    if ((typeof uid === "string" || typeof uid === "number") && typeof o.unique_id === "string" && o.unique_id) {
      into.set(String(uid), o.unique_id);
    }
    Object.values(o).forEach((x) => usernamesFrom(x, into));
  }
  return into;
}

/** Conversation ids look like "0:1:<one user id>:<other user id>". */
export function conversationUsers(bytes: Uint8Array): string[][] {
  const text = new TextDecoder("utf-8", { fatal: false }).decode(bytes);
  return [...text.matchAll(/\d:\d:(\d{6,25}):(\d{6,25})/g)].map((m) => [m[1], m[2]]);
}

/** A short outline of a JSON value (keys and types only), for debugging without exposing content. */
export function outline(v: unknown, depth = 0): unknown {
  if (depth > 5) return "…";
  if (Array.isArray(v)) return v.length ? [outline(v[0], depth + 1), `x${v.length}`] : [];
  if (v && typeof v === "object") {
    return Object.fromEntries(Object.entries(v as Record<string, unknown>).map(([k, x]) => [k, outline(x, depth + 1)]));
  }
  if (typeof v === "string") return /^https?:\/\//.test(v) ? `url:${new URL(v).hostname}` : `str(${v.length})`;
  return typeof v;
}
