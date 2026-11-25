# CareerForge AI — Full-stack working codebase (R1/R2/R3 foundation)

**React + TypeScript + Java Spring Boot + PostgreSQL + Gemini API**. Includes an end-to-end initial implementation of resume analysis, human-reviewed tailoring, interview practice, application tracking, salary evidence, career memory, secure account flows, and subscription infrastructure.

**Status:** Implementation source package, **not production certified**. See [`docs/IMPLEMENTATION_STATUS.md`](docs/IMPLEMENTATION_STATUS.md) for a detailed functionality and go-live gap matrix. A complete real-world R3 SaaS requires live services, more integration work, and rigorous tests.

## Quick start (Windows PowerShell + Docker Desktop)

1. Install Docker Desktop, start it, and ensure Docker Compose is available.
2. Copy `.env.example` to `.env` in the project root.
3. In PowerShell generate a fresh 32-byte JWT key:

   ```powershell
   $bytes = New-Object byte[] 32
   $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
   $rng.GetBytes($bytes)
   [Convert]::ToBase64String($bytes)
   $rng.Dispose()
   ```

4. Put the new value into `JWT_SECRET` in `.env` (do **not** reuse any shared secret). Set `DB_PASSWORD` to a local password. For local testing without Google, set `DEV_AUTH_ENABLED=true` **only in local Docker Compose**. Optionally set `GEMINI_API_KEY` and `GOOGLE_CLIENT_ID`.
5. From the root of this project:

   ```powershell
   docker compose up --build
   ```

6. Open **http://localhost:5173**. If development auth is enabled, click **Local development demo login**. The API is available through `/api/v1/` via the frontend reverse proxy. Mailpit inbox is **http://localhost:8025** for email verification/reset testing.
7. For a first full workflow: Login → My Resumes → paste or upload your resume → Review → Job Analysis → save JD → choose resume version → analyze → review result → Resume Studio → approve edits → export PDF/DOCX. Explore Interview Lab, Job Tracker, Salary Insights, Career Insights, and Settings afterward.

`DEV_AUTH_ENABLED` defaults to `false`; no hardcoded admin or production backdoor exists. Never enable this setting on a public instance. Gemini missing means clearly labeled `LOCAL_HEURISTIC` mode: you will see only basic keyword matching, evidence display, and no contextually rewritten resume.

### Build directly outside Docker

Requires Java 21, Maven 3.9+, Node 22+, PostgreSQL 16+, and a Unicode-capable font available at `PDF_FONT_PATH`.

- `cd backend && mvn spring-boot:run`
- `cd frontend && npm install && npm run dev`

Populate environment variables in your terminal or IDE first. Set `DB_URL=jdbc:postgresql://localhost:5432/careerforge` and ensure the database exists. For separate Vite dev, its proxy forwards `/api` to `localhost:8080`.

## Main routes

| Page | Route |
|---|---|
| Landing | `/` |
| Login / signup / recovery | `/login`, `/register`, `/forgot-password` |
| Dashboard | `/dashboard` |
| Career profile | `/onboarding` |
| Resume library and review | `/resumes`, `/resumes/:id/review` |
| Resume history | `/resumes/:id/versions` |
| Job analysis | `/jobs/new`, `/analyses/:id` |
| Resume Studio | `/tailoring/:id`, `/tailoring` |
| Interview Lab | `/interviews` |
| Job tracker | `/applications` |
| Salary Intelligence | `/salaries` |
| Career Insights | `/insights` |
| Career Memory | `/career-memory` |
| Settings / billing / privacy | `/settings` |

## Architecture

- Spring JDBC and Flyway versioned PostgreSQL migrations. All user-owned reads enforce owner ID derived from the authenticated session.
- HttpOnly browser session cookie, CSRF protection for state changes, database-backed session revocation.
- Gemini API accessed server-side using REST with structured JSON response requests; configuration through environment variables.
- Scheduled PostgreSQL-backed worker for analyses and tailoring. Job status is visible with polling and bounded retries. The queue needs production concurrency/load validation.
- Private local document storage for development. **For Google Cloud deployment, adapt the `DocumentService` storage interface to Cloud Storage with IAM and retention policies.**
- No fabricated salary data: estimates work only from at least three recent user-entered cited observations, and are labeled as nonrepresentative.

## Configure production integrations

- `APP_ORIGIN`: public HTTPS frontend origin.
- `COOKIE_SECURE=true`: mandatory for HTTPS production deployment.
- `GOOGLE_CLIENT_ID`: Google OAuth Web Client ID read from the backend at runtime.
- `SERPAPI_API_KEY` enables online salary research.
- `GEMINI_API_KEY` and `GEMINI_MODEL`: configured Gemini API project/model.
- `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`: verified transactional SMTP.
- `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_PRICE_PRO`: Stripe subscription checkout integration; **test mode first**.
- `STORAGE_ROOT`: private mount, never exposed as static files.
- `JWT_SECRET`: new randomly generated Base64 32+ byte secret, never shared or committed.

All external secrets are backend-only. Frontend has only the public Google OAuth Client ID.

## Engineering checks and known limitations

The build environment used to generate this package had no Maven or network access to registries, so Maven compilation, dependency resolution, database migration, browser E2E, and cloud deployment **could not be executed** here. Java AST syntax parsing and TypeScript syntax transpilation passed. Treat the package as an engineering implementation candidate that must be compiled and tested, not as verified production software.

Tests are in `backend/src/test`. GitHub Actions are in `.github/workflows`. Use the production checklist in `docs/IMPLEMENTATION_STATUS.md` before enabling general user registration.

The reference screenshots are visual inspiration; any claims, metrics, endorsements, salary ranges and invented résumé details appearing inside reference images are not automatically displayed in this implementation.

