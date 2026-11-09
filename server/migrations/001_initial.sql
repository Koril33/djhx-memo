CREATE TABLE users (id TEXT PRIMARY KEY, username TEXT NOT NULL UNIQUE, password_hash TEXT NOT NULL, created_at TEXT NOT NULL);
CREATE TABLE invitations (id TEXT PRIMARY KEY, code_hash TEXT NOT NULL UNIQUE, expires_at TEXT NOT NULL, used_by TEXT REFERENCES users(id), used_at TEXT, revoked_at TEXT);
CREATE TABLE devices (id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE, created_at TEXT NOT NULL, expires_at TEXT NOT NULL, last_seen_at TEXT, revoked_at TEXT);
CREATE INDEX devices_user ON devices(user_id);
CREATE TABLE user_sync_state (user_id TEXT PRIMARY KEY REFERENCES users(id), head_seq INTEGER NOT NULL DEFAULT 0 CHECK(head_seq>=0));
CREATE TABLE notes (user_id TEXT NOT NULL REFERENCES users(id), id TEXT NOT NULL, content TEXT NOT NULL, deleted INTEGER NOT NULL CHECK(deleted IN(0,1)), rev INTEGER NOT NULL CHECK(rev>0), seq INTEGER NOT NULL CHECK(seq>0), created_at TEXT NOT NULL, updated_at TEXT NOT NULL, conflict TEXT, PRIMARY KEY(user_id,id));
CREATE TABLE changes (user_id TEXT NOT NULL REFERENCES users(id), seq INTEGER NOT NULL CHECK(seq>0), snapshot TEXT NOT NULL, PRIMARY KEY(user_id,seq));
CREATE TABLE processed_ops (user_id TEXT NOT NULL REFERENCES users(id), op_id TEXT NOT NULL, fingerprint TEXT NOT NULL, result TEXT NOT NULL, created_at TEXT NOT NULL, PRIMARY KEY(user_id,op_id));
