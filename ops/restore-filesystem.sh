#!/usr/bin/env bash
# Restore a trusted CareerForge backup to a new, empty database and private directory.
set -euo pipefail
umask 077
: "${PG_DSN:?Set the connection string for an empty target database}"
: "${STORAGE_ROOT:?Set an empty private target storage directory}"
: "${1:?Usage: restore-filesystem.sh BACKUP_DIRECTORY}"
backup=$(cd -- "$1" && pwd)
if [ "$(psql "$PG_DSN" -Atqc "SELECT count(*) FROM information_schema.tables WHERE table_schema NOT IN ('pg_catalog','information_schema')")" != 0 ]; then
  echo "Use an empty target database." >&2
  exit 1
fi
if [ -e "$STORAGE_ROOT" ] && [ -n "$(find "$STORAGE_ROOT" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
  echo "Use an empty target storage directory." >&2
  exit 1
fi
(cd "$backup" && sha256sum --check SHA256SUMS)
mkdir -p -- "$STORAGE_ROOT"
tar --no-same-owner -C "$STORAGE_ROOT" -xf "$backup/objects.tar"
pg_restore --single-transaction --exit-on-error --no-owner --no-privileges -d "$PG_DSN" "$backup/database.dump"
printf 'Restored the database and filesystem. Verify the staged application before switching traffic.\n'
