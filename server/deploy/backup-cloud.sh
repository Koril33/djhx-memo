#!/usr/bin/env bash
# Consistent SQLite snapshot, public-key encryption and bounded ciphertext retention.
set -euo pipefail
umask 0077
export LC_ALL=C TZ=UTC

server_bin=/usr/local/bin/memo-server
database=/var/lib/djhx-memo/db/memo.db
epoch_file=/var/lib/djhx-memo/runtime/epoch
snapshot_dir=/var/lib/djhx-memo/backups
export_dir=/var/lib/djhx-memo-export
recipient=/etc/djhx-memo-backup/recipient.pem
while (($#)); do
    (($# >= 2)) || { printf '%s\n' 'Every option requires a value.' >&2; exit 2; }
    case "$1" in
        --server-bin) server_bin=$2 ;;
        --db) database=$2 ;;
        --epoch-file) epoch_file=$2 ;;
        --snapshot-dir) snapshot_dir=$2 ;;
        --export-dir) export_dir=$2 ;;
        --recipient) recipient=$2 ;;
        *) printf '%s\n' 'Unknown backup option.' >&2; exit 2 ;;
    esac
    shift 2
done
for path in "$server_bin" "$database" "$epoch_file" "$snapshot_dir" "$export_dir" "$recipient"; do
    [[ $path == /* && $path != *$'\n'* ]] || { printf '%s\n' 'Backup paths must be absolute.' >&2; exit 2; }
done
[[ -x $server_bin && -f $database && -f $recipient ]]
[[ -d $snapshot_dir && ! -L $snapshot_dir && -d $export_dir && ! -L $export_dir ]]
[[ $(stat -c %a -- "$snapshot_dir") == 700 ]]
[[ $(stat -c %a -- "$export_dir") == 2750 ]]
exec 9>"$snapshot_dir/.backup.lock"
flock -n 9 || { printf '%s\n' 'Another backup is running.' >&2; exit 1; }

work_dir=''
cipher_tmp=''
weekly_tmp=''
finish() {
    local result=$?
    trap - EXIT
    [[ -z $cipher_tmp ]] || rm -f -- "$cipher_tmp"
    [[ -z $weekly_tmp ]] || rm -f -- "$weekly_tmp"
    [[ -z $work_dir ]] || rm -rf -- "$work_dir"
    if ((result != 0)); then
        printf '%s\n' 'Encrypted backup failed; previous completed snapshots were retained.' >&2
    fi
    exit "$result"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
work_dir=$(mktemp -d "$snapshot_dir/.job.XXXXXXXX")
cipher_tmp=$(mktemp "$export_dir/.memo-cipher.XXXXXXXX")
"$server_bin" backup --db "$database" --epoch-file "$epoch_file" --output "$work_dir/snapshot.db"
"$server_bin" check --db "$work_dir/snapshot.db" --epoch-file "$epoch_file"
openssl cms -encrypt -aes-256-gcm -binary -outform DER \
    -in "$work_dir/snapshot.db" -out "$cipher_tmp" \
    -recip "$recipient" -keyopt rsa_padding_mode:oaep -keyopt rsa_oaep_md:sha256
[[ -s $cipher_tmp ]]
openssl cms -cmsout -inform DER -in "$cipher_tmp" -noout
chmod 0640 "$cipher_tmp"
today=$(date -u +%F)
daily="$export_dir/memo-daily-$today.cms"
mv -fT -- "$cipher_tmp" "$daily"
cipher_tmp=''

# Keep the first successful snapshot of each ISO week, indexed by its Monday.
monday=$(date -u -d "$today -$(( $(date -u +%u) - 1 )) days" +%F)
weekly="$export_dir/memo-weekly-$monday.cms"
if [[ ! -e $weekly ]]; then
    weekly_tmp=$(mktemp "$export_dir/.memo-weekly.XXXXXXXX")
    cp --reflink=auto -- "$daily" "$weekly_tmp"
    chmod 0640 "$weekly_tmp"
    mv -T -- "$weekly_tmp" "$weekly"
    weekly_tmp=''
fi

# Count completed snapshots, so an outage never prunes the last useful old copies.
daily_files=()
weekly_files=()
shopt -s nullglob
for file in "$export_dir"/memo-{daily,weekly}-*.cms; do
    name=${file##*/}
    [[ -f $file && ! -L $file ]] || continue
    [[ $name =~ ^memo-(daily|weekly)-([0-9]{4}-[0-9]{2}-[0-9]{2})\.cms$ ]] || continue
    kind=${BASH_REMATCH[1]}
    stamp=${BASH_REMATCH[2]}
    normalized=$(date -u -d "$stamp" +%F 2>/dev/null) || continue
    [[ $normalized == "$stamp" ]] || continue
    if [[ $kind == daily ]]; then daily_files+=("$file");
    elif [[ $(date -u -d "$stamp" +%u) == 1 ]]; then weekly_files+=("$file"); fi
done
if ((${#daily_files[@]} > 7)); then
    mapfile -t daily_files < <(printf '%s\n' "${daily_files[@]}" | sort -r)
    for ((index = 7; index < ${#daily_files[@]}; index++)); do rm -- "${daily_files[index]}"; done
fi
if ((${#weekly_files[@]} > 4)); then
    mapfile -t weekly_files < <(printf '%s\n' "${weekly_files[@]}" | sort -r)
    for ((index = 4; index < ${#weekly_files[@]}; index++)); do rm -- "${weekly_files[index]}"; done
fi
printf '%s\n' 'Encrypted backup completed.'
