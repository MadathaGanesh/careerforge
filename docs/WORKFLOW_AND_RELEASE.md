# CareerForge workflow and release verification

The application runs as a modular Spring Boot/PostgreSQL backend and React frontend. Use the private filesystem for local development or an S3-compatible private bucket for deployment. Flyway V8 and V9 upgrade the audited V1–V7 schema without rewriting the old migrations. Existing password hashes and verification links continue to work.

## What changed

| Audit item | Implementation | Verification |
|---|---|---|
| F01 Account deletion | Interview answer foreign key now cascades. Database rows and sessions are deleted transactionally. Persistent cleanup requests retry file removal. Billing must be terminal and no checkout may be pending. | PostgreSQL smoke suite and populated V7 upgrade fixture |
| F02 Password recovery | Password change, token consumption, and reset session revocation run in one account-locked transaction. | Smoke suite uses multiple browser-equivalent sessions |
| F03 Quota races | Reservations serialize per user and commit with resources and queue entries. Analysis, tailoring, and exports accept a replay key. | Twelve parallel requests yield three accepted analyses and nine plan-limit responses |
| F04 Reviewed version ignored | Confirmation creates immutable versions. New analyses require confirmation. Fit, tailoring, interviews and exports read the chosen text. Analyses keep the reviewed JD snapshot. | Smoke suite tests original preservation and a correction that changes the result |
| F05 PDF loses characters | PDF wrapping measures glyph widths and preserves forced split boundaries. A font error offers DOCX as an alternative. | PDFBox round-trip assertion |
| F06 Jake truncation | Removed the 240-line cap. The export uses reviewed resume contact content. | Late-fact regression and rendered sample |
| F07 Jake download URL | The API helper accepts both API-relative and already-prefixed paths and rejects external hosts. | Vitest and signed-in download checks |
| F08 Jake PDF dependencies | Docker installs TeX packages and fonts. The app compiles a startup fixture, bounds concurrent rendering, and exposes a capability flag. | Local TeX startup and sample PDF. Container execution is a CI gate. The Compose definitions were validated locally. |
| F09 Interview job switch | Changing the job resets the active session, answer, question and feedback. Saved questions and answers can be reopened. | API contract tests and the Chromium/Firefox browser regression passed. |
| F10 Auth abuse and password limits | Configurable deployment controls with database-backed IP and account attempt buckets. New passwords use PBKDF2 and preserve Unicode and whitespace. Legacy BCrypt login remains compatible. | Unit coverage and API tests for Unicode credentials and request throttling |
| F11 Privacy download | Full owned record manifest without application-list caps. ZIP includes original and generated files and records unavailable files. Secrets and internal storage paths are omitted. | Smoke suite read and inspected the ZIP |
| F12 Resume lifecycle | Archive is reversible and retains existing exports. Permanent deletion removes versions, exports and derived analysis, and detaches the resume from retained applications. | Smoke suite covers archive, restore, permanent deletion and cleanup |
| F13 Test configuration | Vitest discovers only unit tests. Playwright runs separately. CI builds both apps and runs integration tests against the deployment image. | The local unit suites and both browser projects passed. CI includes WebKit. |

V8 adds `auth_rate_limits` and `idempotent_requests`. There are 35 application tables, plus Flyway metadata. `privacy_requests` now tracks durable deletion work. Both export tables remain in place to preserve current history and download routes. A later migration can consolidate them if the operational benefit justifies it.

## Website workflow

1. Register with email and a password, then enter the six-digit email code. An already verified user signs in directly. Password reset signs out existing sessions.
2. Google returns a signed ID token. An existing password account must sign in first and confirm its password while linking Google. A Google-only account proves its linked identity again before setting a password. Email changes require reauthentication and a code sent to the new address.
3. Upload PDF/DOCX or paste a resume. Review the text and skills, then confirm an immutable version. Original text and uploads remain available in the account export. Confirmed skills missing from the text are appended. Correct or remove inaccurate text in the review editor.
4. Save a job and review the extracted requirements. Each requirement quote must exist in the saved description. Create a fit analysis with a confirmed resume. Fit v3 weights mandatory skills, experience, responsibilities, preferred skills and education. Each supporting excerpt carries its source offsets. The score is an estimate.
5. Open the saved analysis, generate tailoring proposals, then save and accept correct text or reject suggestions. Background progress survives reload. Transient provider failures retry with bounded delay. Failed tasks with attempts remaining can be retried from the result page.
6. Download Jake PDF or editable Overleaf TEX from Resume Studio. Both use approved edits and reject unresolved, ambiguous or overlapping proposals. Generated versions are drafts until separately confirmed. Export history also lists files generated by earlier releases.
7. Prepare recruiter drafts, practice interviews, manage tracked applications, compare recorded offers, and create reminders. The analysis and interview pickers can page through saved documents and jobs. The tracker can page through older applications. Career Memory sends confirmed facts to the configured AI provider only after opt-in. Local interview mode displays “Not assessed.”
8. Export or delete account data from Settings. Stripe Checkout and the customer portal require provider configuration. Signed events and reconciliation verify the authoritative status and configured price before granting Pro.

## Run locally

Use Docker with Compose, or Java 21, Maven, Node 22, PostgreSQL 16 and SMTP for direct development.

```sh
cp .env.example .env
openssl rand -base64 32
# Put the generated string in JWT_SECRET in .env.
docker compose up --build
```

Open `http://localhost:5173`. Mailpit is at `http://localhost:8025`. Put `DEV_AUTH_ENABLED=true` in a disposable local/test environment to run the synthetic Playwright fixtures. Default local usage requires account verification through Mailpit.

```sh
mvn -f backend/pom.xml verify
cd frontend
npm ci
npm test
npm run build
npx playwright install chromium
npm run test:e2e
```

With disposable Docker services already running and local development login enabled, install `requests` in a Python environment and run `python tests/integration/smoke.py` from the project root. The suite sends synthetic email only. It tests PostgreSQL-backed behavior through HTTP. Configure `CF_TEST_WEBHOOK_SECRET` to the same synthetic value as the server to run webhook signature/replay assertions.

## Release gates that require a deployment environment

| Gate | Required setup and check |
|---|---|
| Browser and accessibility | Run the checked-in Playwright suite in CI. Verify keyboard-only use, focus restoration, mobile widths, browser downloading, and supported browsers before release. |
| Google | Register the exact HTTPS origin, then test sign-in, pre-existing email linking, wrong audience, identity collisions, email change and password setup. |
| Gemini | Configure the model/key and use synthetic and consented calibration data for extraction, tailoring and feedback. Check rate limiting, timeouts, schema rejection and factual evidence. |
| Stripe | Use test mode, set the configured price and webhook secret, enable the portal and cancellation, and run checkout, duplicate, paid/unpaid, renewal, cancellation and out-of-order scenarios. Refunds do not alter access automatically unless Stripe changes the subscription state. |
| Production mail | Validate SMTP hostname, authentication/TLS, delivery, SPF/DKIM/DMARC and bounce handling for your sending domain. |
| Docker and upgrade | Build and run the deployment image, verify the health check and Jake export, and rehearse upgrades against a staging copy first. |
| Storage and recovery | Use a private bucket or durable volume. Keep a legacy volume mounted at its original path for migrated installations. Verify restore of a coordinated database and volume backup, including a download and an account deletion. |
| Load and content | Exercise mixed Unicode and long documents, malformed uploads, concurrent rendering, queue backlog and provider limits. Enable ClamAV over an internal route if required. |

Examples and original product specifications are supplied in the project. The design reference images are available in the audited repository at [docs/ui-references](https://github.com/MadathaGanesh/careerforge/tree/4a831fb57d93c8a52c6b7bd19abb2b6f45bc9547/docs/ui-references).
