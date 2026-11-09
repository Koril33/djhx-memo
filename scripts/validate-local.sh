#!/usr/bin/env bash
# 本机检查入口；依赖已安装的工具链，不启动 CI 或下载全局工具。
set -euo pipefail
cd "$(dirname "$0")/.."
case "${1:-help}" in
  core)
    cargo fmt --manifest-path core/Cargo.toml --check
    cargo clippy --locked --manifest-path core/Cargo.toml --all-targets -- -D warnings
    cargo test --locked --manifest-path core/Cargo.toml
    ;;
  server)
    cd server
    test -z "$(gofmt -l .)"
    go vet ./...
    go test -race ./...
    golangci-lint run
    ;;
  integration)
    mkdir -p .local/server
    (cd server && CGO_ENABLED=0 go build -trimpath -o ../.local/server/memo-server ./cmd/memo-server)
    DJHX_SERVER_BIN="$PWD/.local/server/memo-server" cargo test --locked --manifest-path core/Cargo.toml --test server_integration -- --ignored --nocapture
    python3 server/scripts/smoke.py "$PWD/.local/server/memo-server"
    ;;
  desktop)
    cd desktop
    npm run check
    npm test
    npm run build
    cargo fmt --manifest-path src-tauri/Cargo.toml --check
    # GTK/WebKit development packages are needed; see docs/desktop-validation.md.
    cargo check --locked --manifest-path src-tauri/Cargo.toml
    ;;
  android)
    cd android
    ./gradlew --no-daemon detekt lintDebug testDebugUnitTest assembleDebug assembleRelease
    ;;
  *)
    printf '%s\n' '用法：bash scripts/validate-local.sh core|server|integration|desktop|android' 'Android JNI 先执行 android/scripts/build-native.sh；仪器测试另见 android/README.md。'
    exit 2
    ;;
esac
