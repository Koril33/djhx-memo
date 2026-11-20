DROP TRIGGER notes_update;
CREATE TRIGGER notes_update AFTER UPDATE OF content ON notes WHEN old.content IS NOT new.content BEGIN
  INSERT INTO notes_fts(notes_fts,rowid,content) VALUES('delete',old.rowid,old.content);
  INSERT INTO notes_fts(rowid,content) VALUES(new.rowid,new.content);
END;
