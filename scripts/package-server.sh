#!/usr/bin/env bash
# 构建 Linux amd64 服务端并随包提供部署资料；不访问云服务器。
set -euo pipefail
cd "$(dirname "$0")/.."
memo_revision=$(git rev-parse HEAD)
memo_package="$PWD/dist/server/djhx-memo-server-0.1.0-linux-amd64"
mkdir -p "$memo_package/deploy"
(
  cd server
  CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath \
    -ldflags "-s -w -X main.version=0.1.0-candidate -X main.commit=$memo_revision" \
    -o "$memo_package/memo-server-linux-amd64" ./cmd/memo-server
)
cp server/deploy/djhx-memo.service server/deploy/nginx.conf "$memo_package/deploy/"
cp server/THIRD_PARTY_NOTICES.txt "$memo_package/"
cp docs/operations.md docs/server-validation.md server/README.md "$memo_package/"
printf '%s\n' "version=0.1.0-candidate" "source_commit=$memo_revision" > "$memo_package/BUILD.txt"
tar -C dist/server -czf dist/server/djhx-memo-server-0.1.0-linux-amd64.tar.gz \
  djhx-memo-server-0.1.0-linux-amd64
(
  cd dist/server
  sha256sum djhx-memo-server-0.1.0-linux-amd64.tar.gz \
    djhx-memo-server-0.1.0-linux-amd64/memo-server-linux-amd64 > SHA256SUMS
)
printf '%s\n' '服务端产物：dist/server/；部署和备份恢复见 docs/operations.md。'
