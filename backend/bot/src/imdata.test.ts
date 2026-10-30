import { describe, expect, it } from "vitest";
import { conversationUsers, extractJsonObjects, outline } from "./imdata.ts";

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
