-- The bot's TikTok login. Private: kept here rather than in file storage.
CREATE TABLE bot_state (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  value TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
