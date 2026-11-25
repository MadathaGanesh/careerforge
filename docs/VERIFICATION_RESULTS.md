# Verification results

The release builds on audited CareerForge commit `4a831fb57d93c8a52c6b7bd19abb2b6f45bc9547`. The final source was tested with Java 21, PostgreSQL 16, locked frontend dependencies, TeX Live, and disposable provider fixtures. All test accounts and documents were synthetic.

| Check | Executed result |
|---|---|
| Java unit and provider contracts | 31 passed under Spring Boot 4. Includes JWT startup validation, Windows compiler and font path resolution, absent-renderer handling, and the release's provider and document contracts. |
| Frontend checks | 3 Vitest tests passed. The production build and TypeScript compilation passed. |
| Chromium, Firefox and WebKit | 7 Playwright tests per browser passed with Playwright 1.65.1. Covers both resume downloads and subsequent direct navigation, interview job switching, account recovery and deletion, keyboard dialogs, mobile layouts, Job Tracker, reminders, offers and salary evidence. Chromium used a local headless executable. |
| PostgreSQL API with private filesystem storage | 186/186 assertions passed. Includes account and recovery, ownership, review, scoring snapshots, tailoring, export capabilities, cursor pagination, application updates, reminders, salary data, Career Memory consent, billing-event handling, privacy and file cleanup. |
| PostgreSQL API with S3-compatible storage | 185/185 assertions passed against a disposable S3 emulator. No objects remained for deleted fixture accounts. |
| Documents | Standard and Jake PDFs generated and downloaded. DOCX and TEX preserved approved text. Corpus tests exercised long tokens and pagination, mixed Latin/Greek/Cyrillic, malformed DOCX, DOCX tables, textless PDF and unavailable glyphs. The API enforced the 10 MiB upload cap. |
| Concurrent requests | Twelve simultaneous FREE plan submissions yielded three accepted analyses and nine quota responses. Four concurrent exports each committed a distinct version. |
| Failure recovery and restoration | 28/28 assertions passed. Covers expired queue leases, database and storage failure, orphan cleanup, notification ownership and email delivery, and restoring a PostgreSQL dump together with the private filesystem. The restored original PDF and reviewed data were readable. |
| Existing data upgrade | Started with V1–V7 and existing records. V8 and V9 applied successfully. Legacy session, verification link, BCrypt login, original resume and interview answer survived. The migrated account deleted successfully. |
| Email transport | Authenticated SMTP with required STARTTLS passed using an isolated server and a verified fixture certificate. |
| Read-only load fixture | 120/120 responses succeeded at 10 concurrent workers. Local p95 was 30.6 ms for this dataset and hardware. |
| Dependency scans | npm audit reported zero known findings for the frontend lockfile. The Maven manifest also passed the OSV dependency scan. |
| Docker configuration | Docker Compose validated the local and production definitions. The workspace denied access to the Docker Engine Unix socket, which blocked image builds and container execution. |

The [API smoke record](verification-smoke.json) contains the filesystem run's individual assertions. Several assertions are steps in the same workflow.

## Windows startup and Jake PDF follow-up

- The packaged backend produced separate startup diagnostics for missing, malformed, and short JWT keys. The PowerShell launcher generated a local key and preserved a supplied key in a launch test with PowerShell 7 and a Maven stand-in.
- Jake PDF compiled and downloaded with TeX Live and `LATEX_COMMAND` set to an executable path containing spaces. A separate run with a missing executable passed 183 API assertions and preserved Jake `.tex` and standard PDF exports. Tests for Windows discoverability used temporary directories that model common installation paths.
- Standard PDF generated and downloaded with the default Linux font and no `PDF_FONT_PATH`. Windows font selection was checked with a simulated Windows Fonts directory. A separate API run with a missing font returned `503 PDF_FONT_UNAVAILABLE` with setup guidance and still downloaded DOCX.
- Native Windows IntelliJ and MiKTeX execution still require a check on the target machine.

## Audit recommendations applied in this release

- Added private S3-compatible storage, rollback cleanup, orphan collection, and deletion processing while preserving existing filesystem document keys.
- Enforced six versioned JSON Schemas with runtime validation and generated TypeScript types. Added a five-category evidence rubric with weighted partial credit and source offsets. Scores remain heuristic.
- Moved AI enqueue and resume creation transactions into focused services and ran resume provider work outside the write transaction. Java records cover JSON request bodies and shared TypeScript types cover the main UI responses. Database projections and variable-shape responses still use dynamic maps.
- Added owner/time indexes with Flyway V9, cursor endpoints, paged resumes, jobs, applications and notifications, response limits, and queue metrics and triage queries.
- Added recovery and backup drills, browser and accessibility checks, dependency scans, and a disposable Compose verification script. Compose waits for service health, and downloads use the browser attachment flow. Details for the original F01–F13 fixes are in the [workflow and audit mapping](WORKFLOW_AND_RELEASE.md).

## Remaining release gates

| Area | Remaining verification |
|---|---|
| Docker and deployment | Build and run both images, verify them against deployed infrastructure, inspect image scan results and test Jake PDF inside the container. |
| Browser and accessibility | Check supported devices, Safari on Apple hardware and assistive technology. The executed mobile checks cover selected rules and widths at 360 and 390 pixels. |
| Live integrations | Use staging credentials for Google, Gemini, Stripe Test Mode, transactional email, private object storage and ClamAV. Local protocol and contract tests provide evidence for the behavior they exercise. |
| Production operations | Rehearse a restore with the deployed storage service, measure a representative workload and provider limits, and verify alerting and retention. |

Follow the [release gates](RELEASE_GATE.md) for reproducible staging acceptance steps.

## Screen gallery redesign verification

The redesigned release was verified against a running Java 21 backend, PostgreSQL, local SMTP and the production frontend build. Test accounts, documents and provider credentials were disposable fixtures.

| Check | Executed result |
|---|---|
| Backend and frontend build | 31 Maven tests and 3 Vitest tests passed. The backend package, TypeScript checks and Vite production build completed. |
| Browser workflows | 33 Playwright tests passed across Chromium, Firefox and WebKit. Coverage includes protected-link sign-in, mobile search and the tailoring action, unread notifications on older pages, single upload error feedback, resume review, job fit, tailoring, tracking, interview practice and account workflows. |
| Responsive layout | 24 route patterns rendered at viewport widths of 320, 390 and 1440 pixels with no page-level horizontal overflow. |
| Downloads and data | Playwright exercised standard and Jake PDF, DOCX and Overleaf TEX downloads. The PostgreSQL API smoke run passed 186 assertions, including account and stored-file cleanup. |
| Configured providers | One additional browser test passed for Google fixture sign-in and protected-link restoration. A local integration run passed 58 assertions for Google identity, Gemini and Stripe fixtures. |
| Docker | The local Docker Compose definition validated. Access to the Docker daemon was unavailable in the test environment. Image startup on Docker Desktop and live provider accounts remain unverified for this release. |

## Salary research, Jake exports and Google sign-in update

| Check | Executed result |
|---|---|
| Build | 37 Java tests and 3 frontend tests passed. The backend package and Vite production build completed. |
| Browser regression | 42 tests passed across Chromium, Firefox and WebKit. The salary fixture covered a cited INR range, a blank-result search, reversed experience inputs, mobile layout and the unavailable-provider state. |
| API smoke and persistence | 182 assertions passed against PostgreSQL. The run checked both Jake export routes, idempotency, download ownership, privacy archives and stored-file cleanup. |
| Configured Google | The production frontend read the Web Client ID from the backend, rendered the sign-in button and restored the protected destination after sign-in with a locally signed credential. The Google, Gemini and Stripe provider fixture suite also passed 58 checks. |
| Resume documents | Browsers downloaded Jake PDF and editable Overleaf source. A compiled sample also preserved the candidate email, dated entries and reviewed bullet text. |
| Docker and live services | Both Compose definitions validated. The environment blocked access to the Docker daemon. Live Google and SerpApi credentials are required for deployment acceptance. |

## Salary search button fix

- 42 Playwright tests passed across Chromium, Firefox and WebKit in two runs. With a blank key, a valid submission reached the backend and returned setup guidance. With a local search provider, the UI displayed a cited estimate, insufficient evidence, key rejection and search-limit states.
- 38 Maven tests, 3 frontend tests and 182 PostgreSQL API smoke assertions passed. Local Compose configuration validated. The PowerShell initializer created a blank `SERPAPI_API_KEY` field and preserved an existing value on a second run.
- Salary responses were tested against a local provider fixture. Live salary results still depend on your configured SerpApi key and on the evidence returned for the search.

## Salary source coverage update

- 42 backend tests, 3 frontend tests, and 182 API smoke assertions passed. The local Docker Compose configuration validated.
- 42 Playwright tests passed across Chromium, Firefox and WebKit in configured and unconfigured runs. The Genpact fixture reproduced two completed searches out of four, rendered a city-specific figure with unspecified experience and a broader benchmark, and displayed individual provider failures.
- Additional provider contract checks covered related role titles, salary pages without usable figures, rich result snippets, incompatible pay types and request deadlines. Live results depend on the SerpApi account and available public coverage.

## Searchable selectors and pagination

- The production frontend build, 3 frontend unit tests, 42 backend tests, and all 182 PostgreSQL API smoke assertions passed. The smoke run also generated, downloaded, and re-uploaded a Jake PDF.
- 45 browser tests passed across Chromium, Firefox, and WebKit in the configured salary-fixture run. Coverage includes the 30/31 and 50/51 boundaries, older-record search, literal search characters, archive/restore, keyboard selection, owner isolation, application stage changes, and saved interview questions after quota exhaustion.
- The configured run exercised cited salary figures, related benchmarks, and partial search coverage against a local provider fixture. The missing-key setup path passed in Chromium in a separate run. Live credentials and production provider coverage depend on the deployment.
