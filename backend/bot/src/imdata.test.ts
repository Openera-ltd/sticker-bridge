import { describe, expect, it } from "vitest";
import { botIdFromCookies, conversationUsers, extractJsonObjects, outline, parseImMessages, usernamesFrom } from "./imdata.ts";

/** A protobuf-style length-prefixed string. */
export function lp(s: string): number[] {
  const b = [...new TextEncoder().encode(s)];
  const len = b.length < 128 ? [b.length] : [(b.length & 0x7f) | 0x80, b.length >> 7];
  return [...len, ...b];
}

const BOT = "7604267844595581974";
const USER = "7548886268853404727";
const STICKER_URL =
  "https://p16-tiktok-dm-sticker-sign-sg.ibyteimg.com/tos-alisg-i-dhq7zx4c1p-sg/939cbfd36d2c4381a13796b2289d4b0c~tplv-dhq7zx4c1p-full.awebp?rk3s=00edd399&x-expires=1795967758&x-signature=Y80I3IuP26KepQ9mlx45ck%2FWZO0%3D";

/** One message the way im-api lays it out: conversation id, client message id, then content. */
export function imMessage(conv: string, clientId: string, content: number[]): number[] {
  return [0x0a, ...lp(conv), 0x12, ...lp('{"hack":"1"}'), 0x4a, ...lp("s:client_message_id"), ...lp(clientId), ...content];
}

export function stickerMsg(clientId: string, url = STICKER_URL, from = USER) {
  return imMessage(`0:1:${from}:${BOT}`, clientId, [
    0x22, ...lp(url), 0x2a, ...lp("You sent a sticker"), ...lp("a:sticker_id"), ...lp("129083610934276"),
  ]);
}

export function textMsg(clientId: string, text: string, from = USER) {
  return imMessage(`0:1:${from}:${BOT}`, clientId, [0x22, ...lp(JSON.stringify({ aweType: 0, text }))]);
}

describe("reading stickers and codes from im-api data", () => {
  it("finds each sticker with its exact link, id and sender", () => {
    const data = Uint8Array.from([8, 1, ...stickerMsg("de3294a0-9d81-41e9-abde-69020ce778ba"), ...textMsg("2aa15f0c-1dac-416a-9e0c-ea7c80ec96a3", "my code SB-7KQ4MZ"),
      ...stickerMsg("de3294a0-9d81-41e9-abde-69020ce778ba"), 0x2a, 0x2a]);
    expect(parseImMessages(data)).toEqual([]); // one chat and no bot id: can't tell who's who, so skip
    const msgs = parseImMessages(data, BOT);
    expect(msgs).toEqual([
      { messageId: "de3294a0-9d81-41e9-abde-69020ce778ba", senderId: USER, kind: "sticker", url: STICKER_URL, stickerId: "129083610934276" },
      { messageId: "2aa15f0c-1dac-416a-9e0c-ea7c80ec96a3", senderId: USER, kind: "text", text: "my code SB-7KQ4MZ" },
    ]); // the repeated sticker message is listed once
  });

  it("works out who the bot is from the conversations, and skips the bot's own messages", () => {
    const other = "7417533487765521414";
    const data = Uint8Array.from([
      ...stickerMsg("11111111-1111-1111-1111-111111111111"),
      ...stickerMsg("22222222-2222-2222-2222-222222222222", STICKER_URL, other),
      ...imMessage(`0:1:${BOT}:${BOT}`, "33333333-3333-3333-3333-333333333333", [0x22, ...lp('{"text":"hi"}')]),
    ]);
    expect(parseImMessages(data).map((m) => m.senderId)).toEqual([USER, other]);
  });

  it("ignores TikTok's say-hi suggestion stickers and empty data", () => {
    const greeting = imMessage(`0:1:${USER}:${BOT}`, "44444444-4444-4444-4444-444444444444", [
      0x22, ...lp('{"nickname":"Mia","stickers":[{"static_url":{"url_list":["https://p16-tiktokcdn-com.akamaized.net/obj/tiktok-obj/99b9"]}}]}'),
    ]);
    expect(parseImMessages(Uint8Array.from(greeting))).toEqual([]);
    expect(parseImMessages(new Uint8Array(0))).toEqual([]);
  });

  it("knows its own id from the login cookies", () => {
    const multi = encodeURIComponent(`7562102868365657110:2697bad1|${BOT}:2acb7a33`);
    expect(botIdFromCookies([{ name: "multi_sids", value: multi }, { name: "sessionid", value: "2acb7a33" }])).toBe(BOT);
    expect(botIdFromCookies([{ name: "sessionid", value: "2acb7a33" }])).toBeUndefined();
  });

  it("finds usernames in profile data", () => {
    const m = usernamesFrom({ users: [{ uid: USER, unique_id: "simi.suguru", nickname: "Simi" }, { user: { uid: 1, unique_id: "" } }] });
    expect([...m]).toEqual([[USER, "simi.suguru"]]);
  });
});

const enc = (s: string) => new TextEncoder().encode(s);
const bin = (...parts: (string | number[])[]) => {
  const chunks = parts.map((p) => (typeof p === "string" ? enc(p) : Uint8Array.from(p)));
  const out = new Uint8Array(chunks.reduce((n, c) => n + c.length, 0));
  let o = 0;
  for (const c of chunks) { out.set(c, o); o += c.length; }
  return out;
};

describe("reading TikTok's message data", () => {
  it("finds JSON message contents inside binary data", () => {
    const data = bin([8, 200, 1, 16], "\n+0:1:7417533487765521414:7604267844595581974", [0x10, 1, 0x42, 0x80],
      '{"nickname":"Top 3","stickers":[{"id":6876,"url":{"url_list":["https://p16.tiktokcdn.com/s.webp"]}}]}',
      [0x12, 0x05], '{"text":"SB-7KQ4MZ","braces":"a } inside {"}', [0, 0xff, 0x7b, 0x01]);
    const objs = extractJsonObjects(data) as any[];
    expect(objs).toHaveLength(2);
    expect(objs[0].stickers[0].url.url_list[0]).toBe("https://p16.tiktokcdn.com/s.webp");
    expect(objs[1].text).toBe("SB-7KQ4MZ");
    expect(conversationUsers(data)).toEqual([["7417533487765521414", "7604267844595581974"]]);
  });

  it("outlines structure without exposing content", () => {
    expect(outline({ text: "hello", n: 1, url: "https://p16.tiktokcdn.com/x", list: [{ a: true }, { a: false }] })).toEqual({
      text: "str(5)", n: "number", url: "url:p16.tiktokcdn.com", list: [{ a: "boolean" }, "x2"],
    });
  });
});
