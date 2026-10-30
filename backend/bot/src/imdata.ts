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
