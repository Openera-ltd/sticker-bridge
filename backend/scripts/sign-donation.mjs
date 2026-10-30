#!/usr/bin/env node
// Signs the donation details with the owner's private key and publishes them.
// Usage: ADMIN_SECRET=... node scripts/sign-donation.mjs donation-private.pem '{"paypal":"https://paypal.me/NAME","momo":"+237..."}'
// Keep the private key offline. The app only shows details signed by it.
import { readFileSync } from "node:fs";
import { createPrivateKey, sign } from "node:crypto";

const [keyFile, payload] = process.argv.slice(2);
if (!keyFile || !payload) throw new Error("usage: sign-donation.mjs <private.pem> '<json>'");
JSON.parse(payload); // must be valid JSON
const key = createPrivateKey(readFileSync(keyFile));
const signature = sign("sha256", Buffer.from(payload, "utf8"), key).toString("base64"); // DER, as Android expects
const base = process.env.API_BASE ?? "https://api.sticker.openera.tech";
const res = await fetch(`${base}/admin/donation`, {
  method: "PUT",
  headers: { authorization: `Bearer ${process.env.ADMIN_SECRET}`, "content-type": "application/json" },
  body: JSON.stringify({ payload, signature }),
});
console.log(res.status, await res.text());
