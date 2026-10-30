import { describe, expect, it } from "vitest";
import { imageKey, parseCookies, toInbox } from "./messages";

describe("turning inbox chats into API messages", () => {
  it("keeps only codes and images, with stable ids", async () => {
    const chat = {
      username: "@Mia.Draws",
      userId: "7012345678901234567",
      items: [
        { kind: "text" as const, text: "hi!" },
        { kind: "text" as const, text: "my code SB-7KQ4MZ" },
        { kind: "image" as const, src: "https://p16-sign.tiktokcdn.com/sticker/abc.webp?x-expires=1&sig=a", animated: false },
        { kind: "text" as const, text: "Got it ✅ It's in your pack." },
      ],
    };
    const a = await toInbox(chat);
    expect(a.map((m) => m.type)).toEqual(["text", "sticker"]);
    expect(a[0].sender).toEqual({ id: "7012345678901234567", username: "mia.draws" });
    // Same sticker with a fresh signature = same message id, so it is never stored twice.
    const again = await toInbox({ ...chat, items: [{ kind: "image", src: "https://p16-sign.tiktokcdn.com/sticker/abc.webp?x-expires=2&sig=b", animated: false }] });
    expect(again[0].messageId).toBe(a[1].messageId);
  });

  it("falls back to the username when the page hides the user id", async () => {
    const [m] = await toInbox({ username: "sam_1", userId: null, items: [{ kind: "text", text: "sb 7kq4mz" }] });
    expect(m.sender.id).toBe("u_sam_1");
  });

  it("ignores chats without a real username and non-https images", async () => {
    expect(await toInbox({ username: "", userId: null, items: [{ kind: "text", text: "SB-7KQ4MZ" }] })).toEqual([]);
    expect(await toInbox({ username: "ok", userId: null, items: [{ kind: "image", src: "data:image/png;base64,xx", animated: false }] })).toEqual([]);
  });

  it("image keys ignore expiring signatures", () => {
    expect(imageKey("https://p16-sign-va.tiktokcdn.com/a/b.webp?sig=1")).toBe("tiktokcdn.com/a/b.webp");
  });

  it("reads cookies from a header string or a JSON export", () => {
    expect(parseCookies("sessionid=abc; tt_csrf_token=x=y").map((c) => [c.name, c.value])).toEqual([["sessionid", "abc"], ["tt_csrf_token", "x=y"]]);
    const json = JSON.stringify([{ name: "sessionid", value: "abc", domain: ".tiktok.com", httpOnly: true }]);
    expect(parseCookies(json)[0]).toMatchObject({ name: "sessionid", value: "abc", httpOnly: true, path: "/" });
    expect(parseCookies("")).toEqual([]);
  });
});
