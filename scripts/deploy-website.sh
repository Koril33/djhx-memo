#!/usr/bin/env bash
# Semi-automatic website + update-catalog deployment (orchestration, runs on the
# maintainer's machine). CI builds everything; this downloads the bundle, shows
# a summary, then after confirmation + sudo password uploads and switches the
# server via scripts/deploy-remote-v2.sh. No SSH key or sudo password enters CI.
set -euo pipefail

TAG="${1:?usage: deploy-website.sh <tag>}"
VERSION="${TAG#v}"
SSH_TARGET="${DJHX_DEPLOY_SSH_TARGET:?set DJHX_DEPLOY_SSH_TARGET (e.g. deploy@memo.djhx.site)}"
WWW_ROOT="${DJHX_DEPLOY_WWW_ROOT:-/var/www}"
BUNDLE="deploy-bundle-${VERSION}.tar.gz"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> downloading ${BUNDLE} from release ${TAG}"
gh release download "$TAG" --pattern "${BUNDLE}" --dir "$WORK" --clobber
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
python3 "$SCRIPT_DIR/prepare-download-assets.py" --extract "$WORK/${BUNDLE}" "$WORK"
python3 "$SCRIPT_DIR/prepare-download-assets.py" --verify "$WORK/downloads"
cargo run --offline --locked --release --manifest-path "$SCRIPT_DIR/../core/Cargo.toml" \
  --bin validate-update-catalog -- "$WORK/updates/stable/current.json" \
  --asset windows-x86_64-nsis "$WORK/downloads/releases/$TAG/djhx-memo-$VERSION-windows-x64-setup.exe" \
  --asset android-universal-apk "$WORK/downloads/releases/$TAG/djhx-memo-$VERSION-android.apk"

echo ""
echo "Deploy summary for ${TAG}:"
echo "  version:       ${VERSION}"
echo "  server target: ${SSH_TARGET}"
echo "  site root:     ${WWW_ROOT}/memo.djhx.site"
echo "  site files:    $(find "$WORK/site" -type f | wc -l)"
echo "  catalog:       $(find "$WORK/updates" -type f -printf '%f ' 2>/dev/null || true)"
if command -v python3 >/dev/null 2>&1 && [[ -f "$WORK/updates/stable/current.json" ]]; then
    echo "  new sequence:  $(python3 -c 'import json,base64,sys; e=json.load(open(sys.argv[1],encoding="utf-8")); print(json.loads(base64.b64decode(e["payload_base64"]))["sequence"])' "$WORK/updates/stable/current.json")"
fi

read -r -p "Continue? Type 'yes' to deploy: " CONFIRM
[[ "$CONFIRM" == "yes" ]] || { echo "aborted."; exit 0; }
read -r -s -p "sudo password for ${SSH_TARGET}: " SUDO_PASSWORD
echo ""

STAMP="$(date -u +%Y%m%dT%H%M%SZ)-${TAG}"
REMOTE_STAGING="/tmp/djhx-deploy/${STAMP}"

echo "==> uploading bundle to ${SSH_TARGET} staging"
ssh "$SSH_TARGET" "umask 077; mkdir -p '${REMOTE_STAGING}'"
scp -r "$WORK/site" "$WORK/updates" "$WORK/downloads" "${SSH_TARGET}:${REMOTE_STAGING}/"
scp "$SCRIPT_DIR/prepare-download-assets.py" "$SCRIPT_DIR/publish-download-assets.py" "${SSH_TARGET}:${REMOTE_STAGING}/"
scp "$SCRIPT_DIR/deploy-remote-v2.sh" "${SSH_TARGET}:${REMOTE_STAGING}/deploy-remote.sh"

echo "==> switching site (sudo)"
# sudo password passes on stdin only; the remote script is a file, not stdin.
printf '%s\n' "$SUDO_PASSWORD" | ssh "$SSH_TARGET" \
    "sudo -S -p '' bash '${REMOTE_STAGING}/deploy-remote.sh' '${STAMP}' '${REMOTE_STAGING}' '${WWW_ROOT}'"

ssh "$SSH_TARGET" "rm -rf '${REMOTE_STAGING}'"
echo ""
echo "Deploy complete. The previous release directory is kept for manual rollback; verify then clean up."
