#!/usr/bin/env bash
# Run during a maintenance window after pausing all CareerForge writers.
set -euo pipefail
umask 077
: "${PG_DSN:?Set the PostgreSQL connection string}"
: "${STORAGE_ROOT:?Set the private filesystem storage root}"
: "${1:?Usage: backup-filesystem.sh DESTINATION_DIRECTORY}"
dest=$1
parent=$(dirname -- "$dest")
test -d "$STORAGE_ROOT"
if [ -e "$dest" ]; then
  echo "The backup destination already exists." >&2
  exit 1
fi
temp=$(mktemp -d "$parent/careerforge-backup.XXXXXX")
cleanup() { if [ -n "$temp" ]; then rm -r -- "$temp"; fi; }
trap cleanup EXIT
pg_dump --format=custom --no-owner --no-privileges -d "$PG_DSN" --file "$temp/database.dump"
tar -C "$STORAGE_ROOT" -cf "$temp/objects.tar" .
(
  cd "$temp"
  sha256sum database.dump objects.tar > SHA256SUMS
)
mv -T -- "$temp" "$dest"
temp=
printf 'Verified backup written to %s\n' "$dest"
