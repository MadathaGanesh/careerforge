> Historical baseline document. See [workflow and release verification](WORKFLOW_AND_RELEASE.md) for the updated behavior.

# CareerForge AI — Initial code delivery

## Implementation snapshot

- Monorepo with Java 21 + Spring Boot, React/TypeScript, PostgreSQL migrations, Gemini REST adapter, PDF/DOCX processing, security, and Docker Compose.
- Working source paths for all 10 requested reference screens, plus supporting analysis/review/versions/exports/career-memory views.
- Java controllers for auth, resumes, jobs, analyses, human-controlled tailoring, interviews, applications, salary data, reminders, dashboard, insights, privacy and Stripe checkout/webhooks.
- Local-only development authentication feature flag and synthetic fixtures for first-run tests.
- User-specific data checks, session revocation, CSRF protection, and FREE/PRO monthly quotas.

## Changes required before commercial launch

This is not a validated production release. See `IMPLEMENTATION_STATUS.md` and `PRODUCTION_RUNBOOK.md`.

## Verification executed in authoring environment

- Java parser: 16 main and 3 test files parsed, 0 syntax errors.
- TypeScript parser: 11 files parsed, 0 syntax errors.
- ZIP integrity: verified after archiving.
- **Not executed**: Maven dependency resolution/build/tests; Vite typecheck/build; actual PostgreSQL migrations; Docker Compose; real Google/Gemini/Mail/Stripe integration; browser E2E; performance and security penetration tests.

