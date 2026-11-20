#!/usr/bin/env bash
# Remote privileged half of the website deploy. Runs on the server under sudo,
# invoked by scripts/deploy-website.sh. Copies the staged bundle into a versioned
# release directory, atomically flips the site symlink, verifies a strictly
# higher catalog sequence, reloads nginx and health-checks with rollback.
set -euo pipefail

STAMP="$1"
STAGING="$2"
WWW_ROOT="$3"
SITE_LINK="${WWW_ROOT}/memo.djhx.site"
RELEASES_DIR="${WWW_ROOT}/memo.djhx.site-releases"
UPDATES_SUBDIR="updates/stable"
NEW_DIR="${RELEASES_DIR}/${STAMP}"

mkdir -p "$RELEASES_DIR"
if [[ -e "$NEW_DIR" ]]; then
    echo "error: release dir already exists: ${NEW_DIR}" >&2
    exit 1
fi

# Build the new dir fully before flipping anything.
cp -a "${STAGING}/site" "$NEW_DIR"
cp -a "${STAGING}/updates" "${NEW_DIR}/updates"

OLD_LINK="$(readlink -f "$SITE_LINK" 2>/dev/null || true)"

# Replay guard: the new catalog sequence must be strictly higher than the live one.
NEW_SEQ="$(python3 -c 'import json,base64,sys; e=json.load(open(sys.argv[1],encoding="utf-8")); print(int(json.loads(base64.b64decode(e["payload_base64"]))["sequence"]))' "${NEW_DIR}/${UPDATES_SUBDIR}/current.json")"
LIVE_SEQ="$(curl -fsSL "https://memo.djhx.site/${UPDATES_SUBDIR}/current.json" 2>/dev/null | python3 -c 'import json,base64,sys; e=json.load(sys.stdin); print(int(json.loads(base64.b64decode(e["payload_base64"]))["sequence"]))' 2>/dev/null || echo 0)"
if (( NEW_SEQ <= LIVE_SEQ )); then
    echo "error: new sequence ${NEW_SEQ} not > live ${LIVE_SEQ}" >&2
    exit 1
fi

nginx -t
ln -sfn "$NEW_DIR" "$SITE_LINK"
systemctl reload nginx

# Health check; the first probe can hit the old worker after reload, so retry.
ok=0
for _ in 1 2 3 4 5; do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "https://memo.djhx.site/" || true)"
    if [[ "$code" == "200" ]]; then ok=1; break; fi
    sleep 2
done
if [[ "$ok" != "1" ]]; then
    echo "health check failed; rolling back to ${OLD_LINK:-none}" >&2
    if [[ -n "$OLD_LINK" ]]; then ln -sfn "$OLD_LINK" "$SITE_LINK"; fi
    nginx -t && systemctl reload nginx
    exit 1
fi
echo "deployed ${STAMP}; site -> ${NEW_DIR}"
