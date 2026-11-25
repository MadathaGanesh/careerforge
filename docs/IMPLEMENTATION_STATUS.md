> Historical baseline document. See [workflow and release verification](WORKFLOW_AND_RELEASE.md) for the updated behavior.

# CareerForge AI implementation status — 13 November 2026

This package is a substantive initial codebase. It is **not a certified production release**. Dependencies could not be installed in the build environment, so neither Maven compilation nor a browser-to-PostgreSQL end-to-end run was performed. Java AST syntax parsing and TypeScript syntax transpilation did pass.

## What is implemented in source (not runtime-verified)

| Area | Source | Status / limitation |
|---|---|---|
| React product screens | `frontend/src/pages/` | 10 reference-inspired screens plus supporting views; stateful and API-connected |
| Google Sign-In | `AuthController` | Google JWT JWK validation, audience and issuer checks; requires live client ID and network |
| Email accounts | `AuthController` | Registration, verification, login, reset, local Mailpit; deliverability not verified |
| Sessions | `AuthSecurity`, `V5` | 8-hour HttpOnly JWT session cookie, DB-backed revocation, CSRF token; not penetration-tested |
| PostgreSQL | Flyway V1–V5 | 28+ tables across R1–R3; migrations not executed against PostgreSQL in this environment |
| Resume ingestion | `DocumentService`, `ResumeController` | PDF/DOCX/text, text extraction, reviewable JSON; malware scanner not integrated |
| JD extraction | `CareerAi`, `JobController` | Gemini API or clearly labeled deterministic fallback; schema checks are partial |
| Fit scoring | `CareerAi.fit` | Exact-term evidence heuristic; not semantic fit/ATS predictor |
| Tailoring | `AiWorker`, `TailoringController` | User-approved edits, versions and outputs; factual validation beyond literal-source checks needs strengthening |
| PDF/DOCX | `DocumentService` | Selectable text and basic Unicode font PDF export; visual layout QA not yet performed |
| Interviews | `WorkspaceController`, `Interviews` | Q&A generation, text answer, feedback, session history; browser speech input is experimental |
| Job tracker | `WorkspaceController`, `Applications` | Create/update/drag/drop/notes/status history/reminders |
| Salary research | `WorkspaceController`, `Salaries` | User-entered sourced observations; **no paid data feed, market dataset, or verified salary guarantee** |
| Career insights | `/insights` | Actual saved counts, application stages, scores; no predictive hiring statistics |
| Career memory | `/career-facts` | User-controlled verified facts; not yet integrated with advanced AI retrieval |
| Billing | `BillingController` | Stripe checkout + signed webhook handler skeleton, FREE/PRO quotas; real billing cycles/portal/refund reconciliation not certified |
| Privacy | `/privacy/data-export`, `DELETE /users/me` | JSON export and hard-delete local files; backup/retention policy and restore flow not validated |
| Notifications | `ReminderScheduler` | Due reminder emails via configured SMTP; email idempotency/multi-node delivery needs improvements |
| Cloud deployment | Dockerfiles, Compose | Local containers configured; no actual Google Cloud deployment or load tests |

## Go-live blockers

1. Build backend and frontend with network access, fix any compiler/dependency issues, run all tests.
2. Execute Flyway migrations against a fresh **and** upgraded PostgreSQL database; verify deletes, FK constraints and indexes.
3. Configure Google OAuth, a suitable paid Gemini account, an approved mail provider, proper domain/TLS, and Stripe **test-mode** keys first.
4. Penetration-test authorization, OAuth account linking, sessions/CSRF, uploaded-document parsing, and document download access.
5. Add malware scanning, object-storage permissions, request rate limiting, SSRF protections for any URL import, and improved semantic AI output validation.
6. Validate PDF/DOCX export typography, fonts, multi-page layouts and ATS readability with curated examples.
7. Implement production-grade salary feed/licensing or keep salary feature explicitly limited to user-provided data.
8. Complete billing cancellation, payment failures/refunds, subscriptions lifecycle and accounting reconciliation.
9. Validate voice interactions cross-browser and provide an approved accessible alternative; full live AI voice agent is not included.
10. Complete organization/placement portal, authorized job alerts and integrations, if included in the final R3 definition. They are not present yet.
11. Move from Spring Boot 3.5.x to a currently supported Boot line before public launch, and re-run regression/security tests.
12. Deploy to staging and run end-to-end, accessibility, load, disaster recovery, backup restore and incident drills.

## Non-claims

No feature guarantees ATS acceptance, hiring, salary accuracy, or interview questions. No sample or fictional conversion statistics are presented as actual user results.

