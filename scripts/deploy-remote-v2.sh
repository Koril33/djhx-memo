#!/usr/bin/env bash
# Binary files and the monotonic feed stay independent of website rollback.
# Called only by the maintainer's local, verified deployment process.
set -euo pipefail
# Imported helpers must not leave root-owned cache files in the uploader's staging.
export PYTHONDONTWRITEBYTECODE=1
STAMP="$1"
STAGING="$2"
WWW_ROOT="$3"
[[ "$STAMP" =~ ^[a-zA-Z0-9._-]+$ && "$WWW_ROOT" =~ ^/[a-zA-Z0-9/_-]+$ && "$STAGING" = "/tmp/djhx-deploy/$STAMP" ]] || exit 1
SITE_LINK="${WWW_ROOT}/memo.djhx.site"
RELEASES_DIR="${WWW_ROOT}/memo.djhx.site-releases"
DOWNLOADS="${WWW_ROOT}/memo.djhx.site-downloads"
FEED="${WWW_ROOT}/memo.djhx.site-updates"
NEW_DIR="${RELEASES_DIR}/${STAMP}"
mkdir -p "$RELEASES_DIR" "$DOWNLOADS" "$FEED/stable"
exec 9>"${WWW_ROOT}/.djhx-memo-deploy.lock"
flock -n 9 || { echo 'another deployment is running' >&2; exit 1; }
[[ ! -e "$NEW_DIR" && -f "$DOWNLOADS/latest-deb.conf" ]] || {
    echo 'new site already exists, or one-time download routing is not configured' >&2; exit 1;
}
python3 "$STAGING/prepare-download-assets.py" --verify "$STAGING/downloads"
OLD_LINK="$(readlink -f "$SITE_LINK" 2>/dev/null || true)"
[[ -n "$OLD_LINK" && -f "$OLD_LINK/updates/stable/current.json" ]] || {
    echo 'expected an existing verified deployment' >&2; exit 1;
}
if [[ ! -f "$FEED/stable/current.json" ]]; then cp -a "$OLD_LINK/updates/." "$FEED/"; fi
sequence() { python3 -c 'import json,base64,sys; e=json.load(open(sys.argv[1])); p=json.loads(base64.b64decode(e["payload_base64"],validate=True)); n=p["sequence"]; assert n.isdecimal() and str(int(n))==n; print(n)' "$1"; }
NEW_SEQ="$(sequence "$STAGING/updates/stable/current.json")"
LIVE_SEQ="$(sequence "$FEED/stable/current.json")"
python3 -c 'import sys; assert int(sys.argv[1]) > int(sys.argv[2]), "catalog sequence must increase"' "$NEW_SEQ" "$LIVE_SEQ"
TAG="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["tag"])' "$STAGING/downloads/manifest.json")"
python3 "$STAGING/publish-download-assets.py" --source "$STAGING/downloads" --destination "$DOWNLOADS"
while read -r digest name; do
    actual="$(curl -fsS --max-time 600 "https://memo.djhx.site/downloads/releases/${TAG}/${name}" | sha256sum | cut -d' ' -f1)"
    [[ "$actual" == "$digest" ]] || { echo 'public mirror digest mismatch' >&2; exit 1; }
done < <(python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); [print(v["sha256"], k) for k,v in m["files"].items()]' "$STAGING/downloads/manifest.json")

cp "$DOWNLOADS/latest-deb.conf" "$STAGING/previous-latest.conf"
committed=0
switch_site() {
    ln -s "$1" "${SITE_LINK}.pending-${STAMP}"
    mv -Tf "${SITE_LINK}.pending-${STAMP}" "$SITE_LINK"
}
rollback() {
    if [[ "$committed" == 0 ]]; then
        cp "$STAGING/previous-latest.conf" "$DOWNLOADS/latest-deb.conf"
        switch_site "$OLD_LINK"
        nginx -t && systemctl reload nginx
    else
        echo 'feed promotion started; retain current sequence and repair forward' >&2
    fi
}
trap rollback ERR
cp -a "$STAGING/site" "$NEW_DIR"
[[ ! -e "$NEW_DIR/updates" && ! -e "$NEW_DIR/downloads" ]] || exit 1
ln -s "$FEED" "$NEW_DIR/updates"
printf 'return 302 /downloads/releases/%s/djhx-memo-%s-linux-amd64.deb;\n' "$TAG" "${TAG#v}" > "$DOWNLOADS/.latest.pending"
mv "$DOWNLOADS/.latest.pending" "$DOWNLOADS/latest-deb.conf"
nginx -t
switch_site "$NEW_DIR"
systemctl reload nginx
curl -fsS --retry 4 --retry-delay 2 --max-time 30 https://memo.djhx.site/ > /dev/null
expected="$(sha256sum "$DOWNLOADS/releases/$TAG/djhx-memo-${TAG#v}-linux-amd64.deb" | cut -d' ' -f1)"
actual="$(curl -fsSL --retry 4 --max-time 600 https://memo.djhx.site/downloads/latest/djhx-memo-linux-amd64.deb | sha256sum | cut -d' ' -f1)"
[[ "$actual" == "$expected" ]]
versioned="$FEED/stable/catalog-${NEW_SEQ}.json"
if [[ -e "$versioned" ]]; then cmp "$versioned" "$STAGING/updates/stable/current.json";
else cp "$STAGING/updates/stable/current.json" "$versioned"; fi
cp "$STAGING/updates/stable/current.json" "$FEED/stable/.current.pending"
# Set BEFORE the atomic move so an interrupted handoff cannot replay the feed.
committed=1
mv "$FEED/stable/.current.pending" "$FEED/stable/current.json"
curl -fsS --retry 4 --max-time 30 https://memo.djhx.site/updates/stable/current.json > "$STAGING/live-current.json"
cmp "$STAGING/live-current.json" "$FEED/stable/current.json"
trap - ERR
echo "deployed ${STAMP}; catalog ${NEW_SEQ}; previous files and site retained"
