// Command memo-server runs the v1 HTTP service or a local administrative operation.
package main

import (
	"bufio"
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	memo "git.djhx.site/koril/djhx-memo/server"
)

var version = "0.1.0-dev"
var commit = "unknown"

func main() {
	log.SetFlags(log.LstdFlags | log.LUTC)
	if e := run(os.Args[1:]); e != nil {
		log.Print(e)
		os.Exit(1)
	}
}
func run(args []string) error {
	if len(args) == 0 {
		return errors.New("usage: memo-server serve|invite-create|invite-revoke|password-reset|backup|check|epoch-rotate [flags]")
	}
	command := args[0]
	if command == "version" {
		fmt.Printf("djhx-memo-server %s (%s)\n", version, commit)
		return nil
	}
	flags := flag.NewFlagSet(command, flag.ContinueOnError)
	database := flags.String("db", "./data/memo.db", "SQLite database path")
	epochFile := flags.String("epoch-file", "./runtime/epoch", "independent epoch metadata path")
	listen := flags.String("listen", "127.0.0.1:8080", "HTTP listen address")
	devHTTP := flags.Bool("dev-http", false, "explicitly permit development HTTP")
	proxies := flags.String("trusted-proxies", "127.0.0.1/32,::1/128", "comma-separated trusted proxy CIDRs (empty disables forwarding)")
	registrationDisabled := flags.Bool("registration-disabled", false, "disable invitation registration")
	loginFailures := flags.Int("login-failures", 5, "failed login threshold per IP/account per 15 minutes")
	registerAttempts := flags.Int("register-attempts", 5, "registration attempts per IP per hour")
	id := flags.String("id", "", "invitation ID for invite-revoke")
	username := flags.String("username", "", "account for interactive password-reset")
	output := flags.String("output", "", "new backup destination")
	ttl := flags.Duration("ttl", 7*24*time.Hour, "invitation lifetime")
	if e := flags.Parse(args[1:]); e != nil {
		return e
	}
	if flags.NArg() != 0 {
		return errors.New("unexpected positional arguments")
	}
	ctx := context.Background()
	if command == "epoch-rotate" {
		lock, e := memo.LockEpoch(*epochFile)
		if e != nil {
			return e
		}
		defer func() { _ = lock.Close() }()
		v, e := memo.RotateEpoch(*epochFile)
		if e == nil {
			fmt.Println(v)
		}
		return e
	}
	var epoch string
	var lock *os.File
	if command == "serve" {
		var e error
		lock, e = memo.LockEpoch(*epochFile)
		if e != nil {
			return e
		}
		defer func() { _ = lock.Close() }()
		_, statErr := os.Stat(*database)
		if statErr != nil && !errors.Is(statErr, os.ErrNotExist) {
			return statErr
		}
		epoch, e = memo.LoadEpoch(*epochFile, statErr == nil)
		if e != nil {
			return e
		}
		if !*devHTTP {
			host, _, e := net.SplitHostPort(*listen)
			if e != nil {
				return e
			}
			ip := net.ParseIP(host)
			if ip == nil || !ip.IsLoopback() {
				return errors.New("production listener must be loopback; put HTTPS reverse proxy in front")
			}
		}
	}
	if command != "serve" {
		switch command {
		case "invite-create", "invite-revoke", "password-reset", "backup", "check":
		default:
			return errors.New("unknown command")
		}
		// Fresh administrative initialization also establishes independent metadata,
		// so generating the first invitation before serve is safe.
		if _, statErr := os.Stat(*database); errors.Is(statErr, os.ErrNotExist) {
			if command != "invite-create" {
				return errors.New("database does not exist")
			}
			initialLock, err := memo.LockEpoch(*epochFile)
			if err != nil {
				return err
			}
			_, err = memo.LoadEpoch(*epochFile, false)
			_ = initialLock.Close()
			if err != nil {
				return err
			}
		}
	}
	if e := os.MkdirAll(filepath.Dir(*database), 0700); e != nil {
		return e
	}
	store, e := memo.Open(*database)
	if e != nil {
		return e
	}
	defer func() { _ = store.Close() }()
	switch command {
	case "serve":
		trusted := []string{}
		if *proxies != "" {
			trusted = strings.Split(*proxies, ",")
		}
		api, e := memo.New(store, memo.Config{Epoch: epoch, DevHTTP: *devHTTP, TrustedProxies: trusted, RegistrationDisabled: *registrationDisabled, LoginFailures: *loginFailures, RegisterAttempts: *registerAttempts})
		if e != nil {
			return e
		}
		server := &http.Server{Addr: *listen, Handler: api.Handler(), ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 30 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 16 * 1024}
		stopped := make(chan os.Signal, 1)
		signal.Notify(stopped, syscall.SIGINT, syscall.SIGTERM)
		defer signal.Stop(stopped)
		done := make(chan error, 1)
		go func() { done <- server.ListenAndServe() }()
		log.Printf("memo-server listening on %s", *listen)
		select {
		case err := <-done:
			if errors.Is(err, http.ErrServerClosed) {
				return nil
			}
			return err
		case <-stopped:
			shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			defer cancel()
			_ = api.Shutdown(shutdownCtx)
			return server.Shutdown(shutdownCtx)
		}
	case "invite-create":
		invite, code, e := store.CreateInvitation(ctx, *ttl)
		if e == nil {
			fmt.Printf("invitation_id=%s\ninvite_code=%s\n", invite, code)
		}
		return e
	case "invite-revoke":
		return store.RevokeInvitation(ctx, *id)
	case "password-reset":
		if *username == "" {
			return errors.New("--username is required")
		}
		password, e := readPassword()
		if e != nil {
			return e
		}
		return store.ResetPassword(ctx, *username, password)
	case "backup":
		if *output == "" {
			return errors.New("--output is required")
		}
		return store.Backup(ctx, *output)
	case "check":
		return store.IntegrityCheck(ctx)
	default:
		return errors.New("unknown command")
	}
}
func readPassword() (string, error) {
	tty, e := os.OpenFile("/dev/tty", os.O_RDWR, 0)
	if e != nil {
		return "", errors.New("password reset requires an interactive terminal")
	}
	defer func() { _ = tty.Close() }()
	stateCmd := exec.Command("stty", "-g")
	stateCmd.Stdin = tty
	state, e := stateCmd.Output()
	if e != nil {
		return "", errors.New("unable to read terminal settings")
	}
	set := exec.Command("stty", "-echo")
	set.Stdin = tty
	if e = set.Run(); e != nil {
		return "", e
	}
	defer func() {
		restore := exec.Command("stty", strings.TrimSpace(string(state)))
		restore.Stdin = tty
		_ = restore.Run()
		_, _ = fmt.Fprintln(tty)
	}()
	_, _ = fmt.Fprint(tty, "New password (12-128 characters): ")
	reader := bufio.NewReader(tty)
	first, e := reader.ReadString('\n')
	if e != nil {
		return "", e
	}
	_, _ = fmt.Fprint(tty, "\nConfirm password: ")
	second, e := reader.ReadString('\n')
	if e != nil {
		return "", e
	}
	first = strings.TrimSuffix(strings.TrimSuffix(first, "\n"), "\r")
	second = strings.TrimSuffix(strings.TrimSuffix(second, "\n"), "\r")
	if first != second {
		return "", errors.New("passwords do not match")
	}
	return first, nil
}
