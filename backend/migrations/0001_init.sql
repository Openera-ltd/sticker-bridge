-- TikTok accounts that have connected at least once. id = TikTok's internal user id.
CREATE TABLE accounts (
  id TEXT PRIMARY KEY,
  username TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

-- One row per connected phone. Only the token's SHA-256 is stored.
CREATE TABLE devices (
  token_hash TEXT PRIMARY KEY,
  account_id TEXT NOT NULL,
  install_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  last_seen INTEGER NOT NULL
);
CREATE INDEX devices_account ON devices(account_id);

-- One-time codes shown in the app and sent to @stickerbridge.
CREATE TABLE links (
  code TEXT PRIMARY KEY,
  poll_hash TEXT NOT NULL,
  install_id TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending', -- pending | linked | delivered
  account_id TEXT,
  token TEXT,                               -- plain token, kept only until the app picks it up
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX links_install ON links(install_id, created_at);

-- Stickers received by the bot, per TikTok account. seq is the sync cursor.
CREATE TABLE stickers (
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  id TEXT NOT NULL UNIQUE,
  account_id TEXT NOT NULL,
  r2_key TEXT NOT NULL,
  content_type TEXT NOT NULL,
  animated INTEGER NOT NULL DEFAULT 0,
  emojis TEXT NOT NULL DEFAULT '[]',
  size INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX stickers_account ON stickers(account_id, seq);
CREATE INDEX stickers_created ON stickers(created_at);

-- Bot messages already handled, so retries never double-store.
CREATE TABLE processed (
  message_id TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL
);

-- App releases for in-app updates.
CREATE TABLE releases (
  version_code INTEGER PRIMARY KEY,
  version_name TEXT NOT NULL,
  r2_key TEXT NOT NULL,
  sha256 TEXT NOT NULL,
  size INTEGER NOT NULL,
  notes TEXT NOT NULL DEFAULT '',
  mandatory INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);

-- Small settings the app reads from /v1/config.
CREATE TABLE config (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
