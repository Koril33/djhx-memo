CREATE VIRTUAL TABLE notes_fts USING fts5(content,content='notes',content_rowid='rowid',tokenize='trigram');
CREATE TRIGGER notes_insert AFTER INSERT ON notes BEGIN
 INSERT INTO notes_fts(rowid,content) VALUES(new.rowid,new.content);
END;
CREATE TRIGGER notes_delete AFTER DELETE ON notes BEGIN
 INSERT INTO notes_fts(notes_fts,rowid,content) VALUES('delete',old.rowid,old.content);
END;
CREATE TRIGGER notes_update AFTER UPDATE OF content ON notes BEGIN
 INSERT INTO notes_fts(notes_fts,rowid,content) VALUES('delete',old.rowid,old.content);
 INSERT INTO notes_fts(rowid,content) VALUES(new.rowid,new.content);
END;
INSERT INTO notes_fts(notes_fts) VALUES('rebuild');
