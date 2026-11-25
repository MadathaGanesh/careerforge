> Historical baseline document. See [workflow and release verification](WORKFLOW_AND_RELEASE.md) for the updated behavior.

# Manual end-to-end testing guide

**Environment:** Test only with synthetic or explicitly consented resumes. Start with `docker compose up --build`. Enable `DEV_AUTH_ENABLED=true` only on local development machines.

## Foundation

- [ ] `http://localhost:5173` opens and has correct navigation and responsive layout.
- [ ] `http://localhost:8080/actuator/health` returns UP after PostgreSQL is healthy.
- [ ] Login through development demo creates a user; refreshed browser restores the session; logout invalidates the database session.
- [ ] With development login disabled, `/api/v1/auth/dev` returns 404.
- [ ] A second browser/account cannot open the first account's resume, job, analysis or export by guessing UUIDs.
- [ ] Create real email account; open Mailpit at `http://localhost:8025`; verify token; log in; test password reset.
- [ ] If configured, Google Sign-In validates legitimate credentials and rejects tokens with mismatched audiences.

## Release 1: Resume Intelligence

- [ ] Paste a synthetic resume with Java, Spring Boot, PostgreSQL; confirm structured extraction and re-open it.
- [ ] Upload text-based PDF and DOCX. Confirm they appear and original text is readable.
- [ ] Reject unsupported file, invalid extension, blank text and >10 MB upload.
- [ ] Create JD mentioning Java, Spring Boot, Kafka; correct structured requirements and save.
- [ ] Analyze against uploaded resume version; wait for SUCCEEDED; see Java and Spring Boot evidence and no evidence for Kafka.
- [ ] Confirm the score says heuristic estimate, not an official ATS score.
- [ ] Start tailoring; inspect each proposed original/proposed/rationale/evidence; accept one and reject another.
- [ ] Export PDF and DOCX; open them, extract text, confirm only accepted modifications appear.
- [ ] Confirm master resume remains unchanged and version history includes the new output.
- [ ] Force invalid Gemini credential; observe recoverable task failure without lost resume/job records.

## Release 2: Job Search Workspace

- [ ] Generate role-specific interview questions and verify persistence.
- [ ] Start interview session; submit answer; see feedback and complete session.
- [ ] Add application for saved job; move through Saved/Applied/Interview/Offer; verify event history after refresh.
- [ ] Save notes, offer information and reminder; send reminder using configured SMTP and check Mailpit.
- [ ] Enter 3 sourced salary observations for the same role/city/currency; verify estimated descriptive average.
- [ ] Enter insufficient/incompatible salary data and verify an honest insufficient-data state.
- [ ] Review Career Insights; counts must equal saved application/analysis history.

## Release 3: Business and Privacy

- [ ] Add verified Career Memory fact, reload and confirm persisted evidence.
- [ ] Confirm FREE plan quotas and PRO plan entitlement handling.
- [ ] With Stripe test keys, create checkout, complete a test purchase and validate subscription webhook, duplicate-event handling and plan activation.
- [ ] Test cancellation and payment failures. (This needs additional certification.)
- [ ] Export account data and check user content is present.
- [ ] Delete account with required phrase; confirm current API access is denied and local files are removed.
- [ ] Test keyboard navigation, mobile display, error paths and accessibility.

## Do not launch publicly until

- Maven tests and frontend build pass on CI, Flyway migrations and actual Docker Compose startup work.
- A tested security review and file-scanning solution are in place.
- Gemini quality and document rendering evaluations pass on curated fixtures.
- Backups/restore, webhook lifecycle, Stripe refunds and subscription management are proven.
- Actual salary data licensing and regional validity are established if promising researched salary estimates.

