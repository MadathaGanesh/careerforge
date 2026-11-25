# CareerForge — Redesigned full-stack application

**React + TypeScript + Java 21 + Spring Boot 4 + PostgreSQL**. Includes an implementation of resume analysis, human-reviewed Jake’s Resume tailoring, interview practice, application tracking, online salary research, career memory, secure account flows, and subscription infrastructure.

**Start here.** [Verification results](docs/VERIFICATION_RESULTS.md) records the executed release checks. [Workflow and release verification](docs/WORKFLOW_AND_RELEASE.md) maps the changes to the audit. [Production setup](docs/PRODUCTION_RUNBOOK.md) covers HTTPS, storage, mail, and deployment.

## Quick start (Windows PowerShell + Docker Desktop)

[Install and open Docker Desktop](https://docs.docker.com/desktop/setup/install/windows-install/) in Linux container mode. Stop any existing IntelliJ backend or Vite frontend already using ports 8080 or 5173. Docker builds the Java and React applications and runs PostgreSQL, Mailpit, and the backend's TeX Live and fonts in containers. The local run needs no Windows `LATEX_COMMAND`, `PDF_FONT_PATH`, Java, Maven, Node, PostgreSQL, MiKTeX, or Gmail SMTP configuration.

From the folder containing `docker-compose.yml`:

```powershell
.\scripts\init-docker.ps1
docker compose up --build --wait
```

The initializer creates a local `.env` with a random signing key and PostgreSQL password on the first run. If you already have a Compose `.env`, reuse its JWT key and database password. Your IntelliJ run configuration applies to direct runs.

Open **http://localhost:5173** and register with a test email address. Get the verification code from **http://localhost:8025** and sign in. To test Jake PDF, create a tailoring session, review all proposed edits in **Resume Studio**, then select **Download Jake PDF**. **Download Overleaf .tex** creates editable source for the same template.

| Action | Command |
|---|---|
| Pause the stack | `docker compose stop` |
| Resume the stack | `docker compose up --wait` |
| Stop and remove containers | `docker compose down` |
| View service status | `docker compose ps` |
| Follow backend logs | `docker compose logs -f backend` |
| Check the container's PDFLaTeX | `docker compose exec backend pdflatex --version` |

The PostgreSQL database and uploaded or exported files are persisted in named volumes. `stop`, `down` and rebuilds retain them. Keep the same Compose project and `.env` when using those volumes again. A first Compose run creates its own PostgreSQL database, separate from data in any direct Windows PostgreSQL installation.

The generated configuration supports email sign-in, local extraction and the local fit rubric. `DEV_AUTH_ENABLED` defaults to `false`. Set `GEMINI_API_KEY` to enable Gemini.

### Enable Google sign-in and online salary research

1. Create or use a Google OAuth **Web application** client in [Google Cloud](https://developers.google.com/identity/gsi/web/guides/get-google-api-clientid). Add `http://localhost` and `http://localhost:5173` to **Authorized JavaScript origins** for Docker or Vite testing. Use the site's exact public HTTPS origin in production. If the OAuth app is in testing, add the Google account you will use to its test users.
2. Create a key in [SerpApi](https://serpapi.com/manage-api-key) for its [Google Search API](https://serpapi.com/search-api). Each salary lookup makes one general query and three queries targeting AmbitionBox, Glassdoor and Naukri.
3. Set both values in the **root** `.env` file after running the initializer.

   ```dotenv
   GOOGLE_CLIENT_ID=your-google-web-client-id.apps.googleusercontent.com
   SERPAPI_API_KEY=your-serpapi-key
   ```

4. Run `docker compose up -d --wait` so Compose recreates the backend with these settings. The frontend reads the public Google Client ID at runtime. IntelliJ users can put both values in the backend run configuration, restart Java and refresh the page.

The **Compensation and offers** page searches for the company and job title, using the location on local queries and related role titles where available. It checks the selected experience range, currency, location, and pay basis against the public results. A combined estimate requires comparable annual figures from at least two independent publishers. When a source has broader or incomplete coverage, its link and any usable source-reported figure appear under **Related benchmarks and salary pages**. The **Search coverage** panel reports any incomplete provider requests. Private salary notes and tracker offers are separate from online search.

If salary search reports a missing key, set `SERPAPI_API_KEY` in the root `.env` and run `docker compose up -d --force-recreate --wait backend` from that folder. Container recreation loads the new environment. For IntelliJ, set the key in the backend run configuration and stop and start Java. If SerpApi rejects access or reaches a quota or throughput limit, the result explains which condition to check. A successful search can still report insufficient evidence for a specific role.

### Build directly outside Docker

Requires Java 21, Maven 3.9+, Node 22+, and PostgreSQL 16+. Jake PDF also requires PDFLaTeX.

Start PostgreSQL and create the `careerforge` database. Set `DB_URL`, `DB_USER`, and `DB_PASSWORD` in your terminal or IDE to match your installation. Use `jdbc:postgresql://localhost:5432/careerforge` when PostgreSQL runs on your own machine. Set `MAIL_HOST` and `MAIL_PORT` to your SMTP server for account verification and password recovery.

On Windows, run the backend launcher from the repository root:

```powershell
.\scripts\start-backend.ps1
```

The launcher uses the current `JWT_SECRET` or generates a random key for the current PowerShell session when it is blank. Reuse the same key between restarts to keep existing sessions valid.

For Maven without the launcher, set `JWT_SECRET` before running `mvn spring-boot:run` from `backend`. If you launch from an IDE, set `JWT_SECRET` in that run configuration. Docker Compose reads the root `.env` file. A direct Java, Maven or IDE run reads its process environment.

If startup reports invalid Base64, replace the value at the source used by your launch method. For a new local key in PowerShell:

```powershell
$bytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $rng.GetBytes($bytes)
    $env:JWT_SECRET = [Convert]::ToBase64String($bytes)
} finally {
    $rng.Dispose()
}
```

For an IDE launch on Windows, copy the generated value with `Set-Clipboard -Value $env:JWT_SECRET` and paste it into the `JWT_SECRET` value field. Paste the single-line Base64 text, including any trailing `=`. A different signing key requires users to sign in again.

Run `npm ci` and then `npm run dev` from `frontend` in a separate terminal. The Vite proxy forwards API requests to `localhost:8080`.

### Jake PDF on a direct Windows run

Jake PDF requires PDFLaTeX on the machine running Java. Install [MiKTeX](https://miktex.org/faq/install-miktex-win) or TeX Live. Use the distribution's package manager to ensure `fullpage`, `titlesec`, `enumitem`, `hyperref` and `lmodern` are available, along with the standard LaTeX base, `tabularx` and `glyphtounicode.tex`. MiKTeX's package for `fullpage.sty` is named `preprint`.

Open a new PowerShell window after installing. Verify the binary and launch the backend:

```powershell
$env:LATEX_COMMAND = (Get-Command pdflatex.exe -CommandType Application).Source
& $env:LATEX_COMMAND --version
.\scripts\start-backend.ps1
```

In IntelliJ, edit the backend run configuration and add `LATEX_COMMAND` with the full path to `pdflatex.exe` as its value. If PowerShell cannot find the executable, locate it in your TeX installation and enter that path directly. Restart the backend and refresh Resume Studio. A broken PDFLaTeX setup leaves the backend and Jake `.tex` download available.


## Main routes

| Page | Route |
|---|---|
| Landing | `/` |
| Login / signup / email verification / recovery | `/login`, `/register`, `/verify-email`, `/forgot-password` |
| Dashboard | `/dashboard` |
| Career profile | `/settings#profile` or the existing `/onboarding` route |
| Resume library and review | `/resumes`, `/resumes/:id/review` |
| Resume history | `/resumes/:id/versions` |
| Saved jobs and job details | `/jobs`, `/jobs/:id` |
| Job analysis | `/jobs/new`, `/analyses/:id` |
| Active Resume Studio and export history | `/tailoring/:id`, `/tailoring` |
| Interview Lab | `/interviews` |
| Job tracker | `/applications` |
| Compensation and offers | `/salaries` |
| Career Insights | `/insights` |
| Career facts | `/career-memory` |
| Workspace search and notifications | `/search`, `/notifications` |
| Settings / billing / privacy | `/settings` |

## Architecture

- Spring JDBC and Flyway versioned PostgreSQL migrations. All user-owned reads enforce owner ID derived from the authenticated session.
- HttpOnly browser session cookie, CSRF protection for state changes, database-backed session revocation.
- Gemini API accessed server-side using REST with structured JSON response requests; configuration through environment variables.
- Scheduled PostgreSQL-backed worker for analyses and tailoring. Job status is visible with polling and bounded retries. The queue needs production concurrency/load validation.
- Private filesystem storage for development and an optional S3-compatible object store for deployments. Older local files remain readable from their original mount.
- Online salary research uses public Google results via the backend. Matching evidence is grouped by currency and pay basis, deduplicated by publisher, and linked alongside the median annual range.

## Configure production integrations

- `APP_ORIGIN`: public HTTPS frontend origin.
- `COOKIE_SECURE=true`: mandatory for HTTPS production deployment.
- `GOOGLE_CLIENT_ID` is the OAuth Web Client ID published through `/api/v1/auth/options` and used for Google ID token validation.
- `SERPAPI_API_KEY` enables online salary research. Keep it in the backend environment.
- `GEMINI_API_KEY` and `GEMINI_MODEL`: configured Gemini API project/model.
- `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`: verified transactional SMTP.
- `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_PRICE_PRO`: Stripe subscription checkout integration; **test mode first**.
- `STORAGE_ROOT` for a private mount. Set `STORAGE_BUCKET`, `STORAGE_REGION`, and `STORAGE_PREFIX` for S3-compatible storage.
- `JWT_SECRET`: new randomly generated Base64 32+ byte secret, never shared or committed.

All external secrets are backend-only. Frontend has only the public Google OAuth Client ID.

## Engineering checks and known limitations

The release checks are in `backend/src/test`, `frontend/e2e`, and `tests/integration`. GitHub Actions builds the Compose stack, scans dependencies, and runs browser tests. See the [verification record](docs/VERIFICATION_RESULTS.md) for the results from this package and the [provider readiness checklist](docs/RELEASE_GATE.md) for the deployment steps that require your own test accounts and infrastructure.

The reference screenshots are visual inspiration; any claims, metrics, endorsements, salary ranges and invented résumé details appearing inside reference images are not automatically displayed in this implementation.

