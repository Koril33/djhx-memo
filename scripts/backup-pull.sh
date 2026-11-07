#!/usr/bin/env bash
# Fetch only complete encrypted snapshots; report transport or freshness failures.
set -euo pipefail
umask 0077
export LC_ALL=C TZ=UTC

backup_host=${DJHX_BACKUP_HOST:-koril@memo.djhx.site}
remote_dir=${DJHX_BACKUP_REMOTE_DIR:-/var/lib/djhx-memo-export/}
destination=${DJHX_BACKUP_DEST:-${XDG_DATA_HOME:-$HOME/.local/share}/djhx-memo-backups}
state_dir=${DJHX_BACKUP_STATE:-${XDG_STATE_HOME:-$HOME/.local/state}/djhx-memo-backup}
private_key=${DJHX_BACKUP_PRIVATE_KEY:-$state_dir/keys/private.pem}
recipient=${DJHX_BACKUP_RECIPIENT:-$state_dir/keys/recipient.pem}
[[ $backup_host =~ ^[a-zA-Z0-9._-]+@[a-zA-Z0-9.-]+$ ]]
[[ $remote_dir =~ ^/[a-zA-Z0-9_./-]+/$ && $remote_dir != *'/../'* ]]
for path in "$destination" "$state_dir" "$private_key" "$recipient"; do
    [[ $path == /* && $path != / && $path != *$'\n'* && ! -L $path ]]
done
mkdir -p -- "$destination" "$state_dir"
chmod 0700 -- "$destination" "$state_dir"
exec 9>"$state_dir/.pull.lock"
flock -n 9 || exit 0

status_file="$state_dir/pull.status"
last_success=0
failure_notified=0
newest=0
stage_dir=''
if [[ -f $status_file ]]; then
    last_success=$(sed -n 's/^last_success_at=\([0-9][0-9]*\)$/\1/p' "$status_file")
    [[ $last_success =~ ^[0-9]+$ ]] || last_success=0
    failure_notified=$(sed -n 's/^failure_notified=\([01]\)$/\1/p' "$status_file")
    [[ $failure_notified == 1 ]] || failure_notified=0
fi
finish() {
    local result=$? status=failed checked status_tmp
    trap - EXIT
    [[ -z $stage_dir ]] || rm -rf -- "$stage_dir"
    checked=$(date -u +%s)
    if ((result == 0)); then status=ok; last_success=$checked; failure_notified=0; fi
    if ((result != 0)); then
        printf '%s\n' 'Backup pull, authenticated decryption or 26-hour freshness check failed. Check the backup user service.' >&2
        if ((failure_notified == 0)) && command -v notify-send >/dev/null 2>&1; then
            if notify-send --urgency=critical 'djhx-memo 备份失败' \
                '拉取、认证解密失败，或最新日快照已超过26小时；请检查备份用户服务。' >/dev/null 2>&1; then
                failure_notified=1
            fi
        fi
    fi
    status_tmp=$(mktemp "$state_dir/.status.XXXXXXXX")
    printf 'status=%s\nchecked_at=%s\nlast_success_at=%s\nnewest_snapshot_at=%s\nfailure_notified=%s\n' \
        "$status" "$checked" "$last_success" "$newest" "$failure_notified" >"$status_tmp"
    mv -fT -- "$status_tmp" "$status_file"
    exit "$result"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# No --delete: a server retention run never removes the independent local copies.
# rsync's temporary/partial files are excluded from both success and freshness checks.
[[ -f $private_key && $(stat -c %a -- "$private_key") == 600 && -f $recipient ]]
stage_dir=$(mktemp -d "$destination/.incoming.XXXXXXXX")
rsync -rtp --checksum --quiet --timeout=120 --protect-args --no-links --safe-links \
    --chmod=Du=rwx,Dgo=,Fu=rw,Fgo= --partial-dir=.partial \
    --link-dest="$destination" \
    --include='/memo-daily-[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9].cms' \
    --include='/memo-weekly-[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9].cms' \
    --exclude='*' \
    -e 'ssh -o BatchMode=yes -o IdentityAgent=none -o StrictHostKeyChecking=yes -o ConnectTimeout=15 -o ServerAliveInterval=15 -o ServerAliveCountMax=3' \
    --rsync-path=/usr/bin/rsync -- "$backup_host:$remote_dir" "$stage_dir/"

now=$(date -u +%s)
shopt -s nullglob
validated=()
for file in "$stage_dir"/memo-{daily,weekly}-*.cms; do
    [[ -f $file && ! -L $file && -s $file ]]
    name=${file##*/}
    [[ $name =~ ^memo-(daily|weekly)-([0-9]{4}-[0-9]{2}-[0-9]{2})\.cms$ ]] || continue
    kind=${BASH_REMATCH[1]}
    stamp=${BASH_REMATCH[2]}
    [[ $(date -u -d "$stamp" +%F) == "$stamp" ]]
    # Verify the authentication tag before replacing any previously good copy.
    openssl cms -decrypt -binary -inform DER -in "$file" -recip "$recipient" \
        -inkey "$private_key" -passin pass: -out /dev/null
    validated+=("$file")
    if [[ $kind == daily ]]; then
        modified=$(stat -c %Y -- "$file")
        ((modified <= now + 300))
        if ((modified > newest)); then newest=$modified; fi
    fi
done
fresh=0
if ((newest > 0 && now - newest <= 26 * 60 * 60)); then fresh=1; fi
for file in "${validated[@]}"; do
    target="$destination/${file##*/}"
    # Even a stale but authentic first copy is recoverable. Do not let a stale
    # remote batch replace any same-named local copy previously kept as good.
    if ((fresh == 0)) && [[ -e $target ]]; then continue; fi
    if [[ $file -ef $target ]]; then rm -- "$file";
    else mv -fT -- "$file" "$target"; fi
done
((fresh == 1))
printf '%s\n' 'Encrypted backups fetched; authentication and 26-hour freshness checks passed.'
