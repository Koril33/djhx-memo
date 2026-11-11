ALTER TABLE notes ADD COLUMN purge_state TEXT NOT NULL DEFAULT 'none' CHECK(purge_state IN ('none','pending','rejected','purged'));
CREATE TABLE purge_requests(note_id TEXT PRIMARY KEY REFERENCES notes(id), op_id TEXT NOT NULL UNIQUE, base_rev INTEGER);
