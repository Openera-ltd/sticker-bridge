-- A re-made sticker (e.g. now animated) names the one it replaces, so phones swap it in place.
ALTER TABLE stickers ADD COLUMN replaces TEXT;
