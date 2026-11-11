#!/usr/bin/env bash
# 构建 Linux amd64 服务端并随包提供部署资料；不访问云服务器。
set -euo pipefail
cd "$(dirname "$0")/.."
memo_version=0.1.4
memo_revision=$(git rev-parse HEAD)
memo_name="djhx-memo-server-$memo_version-linux-amd64"
memo_package="$PWD/dist/server/$memo_name"
mkdir -p "$memo_package/deploy"
(
  cd server
  CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath \
    -ldflags "-s -w -X main.version=$memo_version -X main.commit=$memo_revision" \
    -o "$memo_package/memo-server-linux-amd64" ./cmd/memo-server
)
cp -R server/deploy/. "$memo_package/deploy/"
cp scripts/backup-pull.sh "$memo_package/deploy/"
cp server/THIRD_PARTY_NOTICES.txt LICENSE "$memo_package/"
cp docs/operations.md docs/server-validation.md docs/cloud-deployment.md \
  docs/backup-local.md server/README.md "$memo_package/"
printf '%s\n' "version=$memo_version" "source_commit=$memo_revision" > "$memo_package/BUILD.txt"
tar -C dist/server -czf "dist/server/$memo_name.tar.gz" "$memo_name"
(
  cd dist/server
  sha256sum "$memo_name.tar.gz" "$memo_name/memo-server-linux-amd64" > SHA256SUMS
)
printf '%s\n' '服务端产物：dist/server/；部署和备份恢复见 docs/operations.md。'
