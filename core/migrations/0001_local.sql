CREATE TABLE notes (
 id TEXT PRIMARY KEY, content TEXT NOT NULL, deleted INTEGER NOT NULL DEFAULT 0,
 generation INTEGER NOT NULL CHECK(generation>0), acknowledged INTEGER NOT NULL DEFAULT 0,
 base_rev INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
 conflict TEXT
);
CREATE TABLE note_shadows(id TEXT PRIMARY KEY, snapshot TEXT NOT NULL);
CREATE TABLE outbox(
 op_id TEXT PRIMARY KEY, note_id TEXT NOT NULL UNIQUE REFERENCES notes(id),
 snapshot TEXT NOT NULL, frozen_generation INTEGER NOT NULL, blocked TEXT
);
CREATE TABLE sync_state(key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE imports(source_profile_id TEXT NOT NULL,source_note_id TEXT NOT NULL,source_generation INTEGER NOT NULL,target_note_id TEXT NOT NULL,
 PRIMARY KEY(source_profile_id,source_note_id,source_generation));
CREATE TABLE local_requests(request_id TEXT PRIMARY KEY,note_id TEXT NOT NULL REFERENCES notes(id));
CREATE TABLE redirects(source_id TEXT PRIMARY KEY,target_id TEXT NOT NULL,source_generation INTEGER NOT NULL,target_generation INTEGER NOT NULL);
CREATE INDEX notes_order ON notes(deleted,updated_at DESC,id);
