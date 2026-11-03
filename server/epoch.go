package memo

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"syscall"
)

// LoadEpoch reads an independent recovery epoch, initializing only a genuinely new deployment.
// An existing database with missing metadata must be recovered explicitly using RotateEpoch.
func LoadEpoch(path string, databaseExists bool) (string, error) {
	b, e := os.ReadFile(path)
	if e == nil {
		v := strings.TrimSpace(string(b))
		if !uuidPattern.MatchString(v) {
			return "", errors.New("invalid epoch metadata; stop service and rotate epoch")
		}
		return v, nil
	}
	if !errors.Is(e, os.ErrNotExist) {
		return "", e
	}
	if databaseExists {
		return "", errors.New("epoch metadata missing for existing database; stop service and rotate epoch")
	}
	return RotateEpoch(path)
}

// LockEpoch prevents multiple server instances and online epoch rotation. Keep the lock open.
func LockEpoch(path string) (*os.File, error) {
	if e := os.MkdirAll(filepath.Dir(path), 0700); e != nil {
		return nil, e
	}
	f, e := os.OpenFile(path+".lock", os.O_CREATE|os.O_RDWR, 0600)
	if e != nil {
		return nil, e
	}
	if e = syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); e != nil {
		_ = f.Close()
		return nil, errors.New("server is running or epoch is locked; stop service first")
	}
	return f, nil
}

// RotateEpoch atomically persists a fresh recovery epoch. Caller must hold LockEpoch.
func RotateEpoch(path string) (string, error) {
	if e := os.MkdirAll(filepath.Dir(path), 0700); e != nil {
		return "", e
	}
	f, e := os.CreateTemp(filepath.Dir(path), ".epoch-*")
	if e != nil {
		return "", e
	}
	defer func() { _ = os.Remove(f.Name()) }()
	v := uuid()
	if _, e = f.WriteString(v + "\n"); e != nil {
		_ = f.Close()
		return "", e
	}
	if e = f.Sync(); e != nil {
		_ = f.Close()
		return "", e
	}
	if e = f.Close(); e != nil {
		return "", e
	}
	if e = os.Rename(f.Name(), path); e != nil {
		return "", e
	}
	dir, e := os.Open(filepath.Dir(path))
	if e != nil {
		return "", e
	}
	defer func() { _ = dir.Close() }()
	if e = dir.Sync(); e != nil {
		return "", e
	}
	return v, nil
}
