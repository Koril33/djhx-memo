package memo

import (
	"archive/zip"
	"database/sql"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"strings"
	"unicode/utf8"
)

type manifestNote struct {
	ID       string    `json:"id"`
	File     string    `json:"file"`
	Title    string    `json:"title"`
	Deleted  bool      `json:"deleted"`
	Conflict *Conflict `json:"conflict"`
}
type manifest struct {
	FormatVersion int            `json:"format_version"`
	ExportedAt    string         `json:"exported_at"`
	SourceKind    string         `json:"source_kind"`
	Notes         []manifestNote `json:"notes"`
}

func noteTitle(content string) string {
	for _, line := range strings.Split(content, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		line = strings.TrimSpace(strings.TrimLeft(line, "#"))
		if line == "" {
			return "无标题"
		}
		if utf8.RuneCountInString(line) > 80 {
			line = string([]rune(line)[:80])
		}
		return line
	}
	return "无标题"
}
func (s *Server) exportHTTP(w http.ResponseWriter, r *http.Request, a identity) {
	q := r.URL.Query()
	include := false
	for k, values := range q {
		if k != "include_deleted" || len(values) != 1 || (values[0] != "true" && values[0] != "false") {
			writeError(w, r, apiError("INVALID_REQUEST"))
			return
		}
		include = values[0] == "true"
	}
	f, e := os.CreateTemp("", "djhx-export-*.zip")
	if e != nil {
		writeError(w, r, e)
		return
	}
	defer func() { _ = f.Close(); _ = os.Remove(f.Name()) }()
	tx, e := s.store.db.BeginTx(r.Context(), &sql.TxOptions{ReadOnly: true})
	if e != nil {
		writeError(w, r, e)
		return
	}
	defer func() { _ = tx.Rollback() }()
	rows, e := tx.QueryContext(r.Context(), "SELECT "+noteColumns+" FROM notes WHERE user_id=? AND purged=0 AND (deleted=0 OR ?) ORDER BY id", a.UserID, include)
	if e != nil {
		writeError(w, r, e)
		return
	}
	zw := zip.NewWriter(f)
	m := manifest{1, stamp(s.store.now()), "server", []manifestNote{}}
	for rows.Next() {
		n, err := scanNote(rows)
		if err != nil {
			e = err
			break
		}
		var entry io.Writer
		entry, err = zw.Create(n.ID + ".md")
		if err != nil {
			e = err
			break
		}
		if _, err = io.WriteString(entry, n.Content); err != nil {
			e = err
			break
		}
		m.Notes = append(m.Notes, manifestNote{n.ID, n.ID + ".md", noteTitle(n.Content), n.Deleted, n.Conflict})
	}
	if e == nil {
		e = rows.Err()
	}
	_ = rows.Close()
	if e == nil {
		e = tx.Commit()
	}
	if e != nil {
		_ = zw.Close()
		writeError(w, r, e)
		return
	}
	entry, e := zw.Create("manifest.json")
	if e == nil {
		e = json.NewEncoder(entry).Encode(m)
	}
	if e == nil {
		e = zw.Close()
	} else {
		_ = zw.Close()
	}
	if e == nil {
		e = f.Sync()
	}
	if e == nil {
		_, e = f.Seek(0, io.SeekStart)
	}
	if e != nil {
		writeError(w, r, e)
		return
	}
	stat, e := f.Stat()
	if e != nil {
		writeError(w, r, e)
		return
	}
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", `attachment; filename="djhx-memo-export.zip"`)
	w.Header().Set("Content-Length", num(stat.Size()))
	w.WriteHeader(200)
	_, _ = io.Copy(w, f)
}
