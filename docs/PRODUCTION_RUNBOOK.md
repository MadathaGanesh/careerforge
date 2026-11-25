# CareerForge production runbook

Use the local Compose file with disposable data. For a single production instance, provide managed PostgreSQL, production SMTP and a reverse proxy terminating HTTPS in front of the web listener. The backend and database stay on internal networks. Set `APP_ORIGIN` to the exact public HTTPS origin. Keep `COOKIE_SECURE=true` and `DEV_AUTH_ENABLED=false`.

```sh
docker compose -f compose.production.yml up --build -d
```

Configure the proxy, database, storage volume, secret manager and providers before running the command. Set database TLS options in `DB_URL` appropriate to your database installation. If a reverse proxy terminates HTTPS, configure Nginx real-IP handling for that proxy's exact trusted addresses and verify that separate clients receive separate IP rate-limit buckets. For ClamAV uploads, set `CLAMAV_HOST` and `CLAMAV_PORT` to the address of a private, updated clamd server. Configure a spill-safe storage path with TeX Live available for Jake PDF.

## Deploy and migrate

1. Take a coordinated PostgreSQL backup and volume snapshot before a schema upgrade.
2. Rehearse restoring both into isolated staging. Use the same mount path referenced by saved files.
3. Run Flyway upgrades from the new service image. Never edit migrations that have already run.
4. Check the health endpoint, resume workflow, a stored file download, the renderer capability, and login/logout from the exact configured origin.
5. If reverting a deployment, restore the compatible database and file snapshots together where needed. A previous binary may not be compatible with changes in account and archive state.

## Monitor

- Alert on sustained health failures, database pool exhaustion and failed app startup.
- Track AI jobs by status, oldest due `next_run_at`, leases, and error code.
- Check overdue privacy cleanup and notification outbox entries. Inspect failures without exporting user content into logs.
- Compare Stripe plan/status/reference reconciliation against the provider dashboard.
- Set alerting and support procedures for checkouts that remain unresolved beyond the provider's idempotency window. Resolve the provider state before clearing an outstanding reservation.
- Set a SerpApi search budget if online salary research is enabled. Each submitted search uses up to four calls. Public queries contain the role, company, location, currency and experience range. Keep the search key on the backend and check provider terms for the deployment.
- Set storage, request, queue, render, SMTP and AI usage budgets. Inspect query plans and resource metrics under realistic load before increasing throughput or adding replicas.

## Backup, retention and recovery

Use encrypted, restricted backups with a documented retention period. Exclude local mail test data. Rehearse recovery with synthetic accounts. Verify that the database and file snapshots agree and that the private storage mount stays isolated from any static server. Process cleanup work again before resuming service after restoration, so deleted items are not silently returned to the live site.

SQLite and shared ephemeral disk are outside this deployment layout. Scaling to multiple API instances requires consistently shared durable storage or an object-storage adapter, and a deployment-level backup and recovery exercise.

Spring properties `app.auth-rate-limit.<action>.account-requests`, `ip-requests` and `window-seconds` override defaults for public auth routes. Configure proxy client-IP forwarding before tuning IP limits.

## Private object storage and diagnostics

Set `STORAGE_BUCKET`, `STORAGE_REGION` and `STORAGE_PREFIX` to a dedicated application namespace. The AWS SDK uses its deployment credential chain. Give the application role access only to the configured namespace and enforce private access, TLS, encryption and retention in the provider. Set `STORAGE_ENDPOINT` and `STORAGE_PATH_STYLE` only when the S3-compatible service requires an override. Preserve the original private filesystem mount during migration. Existing objects keep their backend-specific keys.

The cleanup worker removes data for deleted accounts. The janitor checks for old unreferenced uploads and exports after a grace period. If bucket versioning is enabled, configure noncurrent version expiration to match policy and validate it in staging.

Enable `OPERATIONS_JMX_ENABLED=true` for local JMX collection of queue and cleanup gauges. Keep management access on the internal monitoring plane. Run `ops/triage.sql` with a restricted operations role for aggregate queue, failure and reconciliation summaries.

`ops/backup-filesystem.sh` creates a PostgreSQL archive, object archive and checksums after writers are paused. `ops/restore-filesystem.sh` accepts that trusted archive and requires a fresh database and private directory. For object storage, take a coordinated database snapshot and provider-side recovery point, then rehearse the exact policy and restore sequence in staging.
