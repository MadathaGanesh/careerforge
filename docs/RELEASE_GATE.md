# Release gates and provider staging

Verify the release using synthetic accounts, sample resumes and sandbox billing. Keep the result of each gate with the release identifier. The package includes an executed verification record and test tooling.

## Container and cross-browser run

1. Use a Docker-enabled runner with Java 21, Node 22 and a local Python where `requests` is installed. In `frontend`, run `npm ci` and `npx playwright install --with-deps chromium firefox webkit`.
2. Run `bash scripts/verify-compose.sh` at the repository root with a recent Docker Compose v2. It waits for the database, backend and proxied frontend health checks, runs the API suite and browser matrix, and removes that project's disposable volumes.
3. Confirm the compiled renderer capability and Jake PDF inside the backend container. The API suite requires a generated and downloaded PDF by default.
4. Run the same tests after an upgrade from a copy of the last deployed database and storage snapshot. Exercise a pre-upgrade account, a previously stored file and a new export.

## Configured provider acceptance

| Service | What to verify in the staging account |
|---|---|
| Google | Create a verified Google-only account, sign out and sign in again, try a mismatched application client ID, verify that a pre-existing password account requires linking, link after password reauthentication, set a password for a Google-only user, request and confirm a new email. Review application access after revoking the Google grant. |
| Gemini | Run extraction, tailoring and interview feedback on curated synthetic pairs. Compare claims against reviewed text. Inspect schema errors, prompt isolation, 429 and timeout recovery. Review quality on long and multilingual documents and check actual usage against your budget. |
| Stripe | Use Stripe test mode. Open checkout twice, compare the pending checkout session, complete the purchase, verify a signed update grants the correct entitlement, replay and reorder events, reconcile a missed event, exercise a renewal, payment failure, portal cancellation, and paid account deletion. Review refund handling against the subscription state. |
| Transactional email | Use the configured provider and controlled test recipients. Confirm TLS and authenticated delivery of signup, reset and email-change messages. Verify bounce, retry, suppression and domain-authentication monitoring in the provider. |
| Private object storage | Validate bucket access with the application role, disallow public reads, test upload/download/delete, and verify account and staging restore behavior. Review object version retention and backups under your policy. |
| Upload scanner | Validate connection to a private ClamAV service and test clean, rejected, unavailable and timeout behavior in staging. |

## Recovery, throughput and accessibility

- Run `tests/integration/resilience.py` against a disposable API, database and local document storage. It creates synthetic failures, reclaims an expired task, verifies cleanup and runs the backup and restore scripts against a fresh database.
- Run `tests/integration/read_load.py` with the disposable fixture. Then benchmark the actual staging deployment with representative dataset size, document lengths, concurrent writers, and provider quota limits.
- Record container image scans, CI results, job/export latency, queue depth and notification failures during the staging soak.
- Test supported browsers and devices, keyboard interaction, screen-reader announcements, contrast, zoom and localization. The browser suite checks selected accessibility rules at widths of 360 and 390 pixels. Manual accessibility and real-device coverage are additional gates.
