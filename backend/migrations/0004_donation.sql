-- Donation details shown in the app. The payload is signed offline (ECDSA P-256); the app checks the
-- signature with its built-in public key, so a changed row without the private key is ignored.
CREATE TABLE donation (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  payload TEXT NOT NULL,
  signature TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
