/**
 * Reading TikTok's web inbox. TikTok's page layout is undocumented and changes;
 * everything that depends on it lives in this file. If the bot logs in but finds
 * no chats, adjust ROW_SELECTOR / CHAT_SELECTOR / BUBBLE_SELECTOR.
 */
import type { Page } from "puppeteer-core";
import type { SeenChat } from "./messages.ts";

export const ROW_SELECTOR = '[data-e2e*="chat-list-item"], [data-e2e*="conversation"], [class*="ConversationListItem"], div[data-index]';
export const CHAT_SELECTOR = '[data-e2e*="chat-main"], [class*="ChatMain"], [class*="ChatBox"], main';
export const BUBBLE_SELECTOR = '[data-e2e*="chat-item"], [class*="ChatItem"], [class*="MessageItem"]';

export async function readInbox(page: Page, inboxUrl: string, maxChats: number): Promise<SeenChat[]> {
  // TikTok keeps network requests open, so wait for the page structure, then for the chat list.
  await page.goto(inboxUrl, { waitUntil: "domcontentloaded", timeout: 45_000 });
  await page.waitForSelector(ROW_SELECTOR, { timeout: 30_000 });
  const count = Math.min(maxChats, await page.$$eval(ROW_SELECTOR, (els) => els.length));
  const chats: SeenChat[] = [];

  for (let i = 0; i < count; i++) {
    const rows = await page.$$(ROW_SELECTOR);
    if (!rows[i]) break;
    await rows[i].click();
    await new Promise((r) => setTimeout(r, 1500));
    const chat = await page.evaluate(
      (chatSel, bubbleSel) => {
        const main = (document.querySelector(chatSel) ?? document.body) as HTMLElement;
        // Who we're talking to: the profile link in the chat header.
        const link = main.querySelector('a[href*="/@"]') as HTMLAnchorElement | null;
        const username = link ? ((link.getAttribute("href") ?? "").split("/@")[1] ?? "").split(/[/?#]/)[0] : "";
        const url = new URL(location.href);
        const userId = url.searchParams.get("u") ?? url.searchParams.get("uid") ?? null;
        // Messages, newest last. Avatars are skipped (small, or inside profile links).
        const items: ({ kind: "text"; text: string } | { kind: "image"; src: string; animated: boolean })[] = [];
        const bubbles = main.querySelectorAll(bubbleSel);
        const scope: Element[] = bubbles.length ? [...bubbles] : [main];
        for (const el of scope.slice(-30)) {
          for (const img of el.querySelectorAll("img")) {
            const r = img.getBoundingClientRect();
            if (img.closest('a[href*="/@"]') || r.width < 56 || r.height < 56) continue;
            const src = img.currentSrc || img.src;
            if (src) items.push({ kind: "image", src, animated: /\.gif|awebp|animated/i.test(src) });
          }
          const text = (el as HTMLElement).innerText?.trim();
          if (text && text.length < 200) items.push({ kind: "text", text });
        }
        return { username, userId, items };
      },
      CHAT_SELECTOR,
      BUBBLE_SELECTOR,
    );
    if (chat.username) chats.push(chat);
  }
  return chats;
}

/** Types a reply into the open chat. */
export async function reply(page: Page, text: string): Promise<void> {
  const box = await page.$('[contenteditable="true"], .public-DraftEditor-content, textarea');
  if (!box) return;
  await box.click();
  await page.keyboard.type(text, { delay: 15 });
  await page.keyboard.press("Enter");
  await new Promise((r) => setTimeout(r, 600));
}
