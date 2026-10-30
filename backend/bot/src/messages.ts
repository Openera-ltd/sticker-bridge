/**
 * Pure helpers for turning what the bot sees in TikTok's inbox into API messages.
 * Kept free of browser code so they can be tested on their own.
 */

/** What the page script returns for one conversation. */
export interface SeenChat {
  /** TikTok username of the other person, without "@". */
  username: string;
  /** TikTok's internal user id, when the page shows it. */
  userId: string | null;
  items: SeenItem[];
}

export type SeenItem = { kind: "text"; text: string } | { kind: "image"; src: string; animated: boolean };

export interface InboxMessage {
  messageId: string;
  sender: { id: string; username: string };
  type: "text" | "sticker";
  text?: string;
  url?: string;
  animated?: boolean;
}

/** Stable id for a message: same chat + same content = same id, so re-reading the inbox never double-counts. */
export async function messageId(parts: string[]): Promise<string> {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(parts.join("\u0000")));
  return [...new Uint8Array(d)].slice(0, 16).map((x) => x.toString(16).padStart(2, "0")).join("");
}

/** TikTok CDN links carry expiring signatures in the query; the path identifies the image. */
export function imageKey(src: string): string {
  try {
    const u = new URL(src);
    return u.hostname.split(".").slice(-2).join(".") + u.pathname;
  } catch {
    return src;
  }
}

const CODE = /SB\s*-?\s*[23456789A-HJ-NP-Z]{6}/i;

/** Only codes and images matter; other chat (including the bot's own replies) is skipped. */
export async function toInbox(chat: SeenChat): Promise<InboxMessage[]> {
  const username = chat.username.replace(/^@/, "").trim().toLowerCase();
  if (!/^[a-z0-9._]{2,24}$/.test(username)) return [];
  const id = chat.userId && /^\d{5,25}$/.test(chat.userId) ? chat.userId : `u:${username}`;
  const sender = { id: id.replace(/[^A-Za-z0-9_-]/g, "_"), username };
  const out: InboxMessage[] = [];
  for (const item of chat.items) {
    if (item.kind === "text") {
      if (!CODE.test(item.text)) continue;
      out.push({ messageId: await messageId([sender.id, "text", item.text.trim().toUpperCase()]), sender, type: "text", text: item.text });
    } else if (item.src.startsWith("https://")) {
      out.push({
        messageId: await messageId([sender.id, "img", imageKey(item.src)]),
        sender,
        type: "sticker",
        url: item.src,
        animated: item.animated,
      });
    }
  }
  return out;
}

/** Accepts cookies as a Cookie header ("a=1; b=2") or a browser-extension JSON export. */
export function parseCookies(raw: string): { name: string; value: string; domain: string; path: string; secure: boolean; httpOnly: boolean }[] {
  const t = raw.trim();
  if (!t) return [];
  if (t.startsWith("[")) {
    const list = JSON.parse(t) as { name: string; value: string; domain?: string; path?: string; secure?: boolean; httpOnly?: boolean }[];
    return list
      .filter((c) => c.name && typeof c.value === "string")
      .map((c) => ({ name: c.name, value: c.value, domain: c.domain ?? ".tiktok.com", path: c.path ?? "/", secure: c.secure ?? true, httpOnly: c.httpOnly ?? false }));
  }
  return t.split(";").map((p) => p.trim()).filter(Boolean).map((p) => {
    const i = p.indexOf("=");
    return { name: p.slice(0, i), value: p.slice(i + 1), domain: ".tiktok.com", path: "/", secure: true, httpOnly: false };
  }).filter((c) => c.name);
}
