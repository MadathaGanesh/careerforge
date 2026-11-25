# Route index generated from Java controller annotations

This index lists controller routes and summarizes selected request contracts. Java request records are in `backend/src/main/java/com/careerforge/api`. Shared TypeScript response types and generated AI contracts are in `frontend/src/lib`.

|Method|Path|Java source|
|---|---|---|
|POST|`/api/v1/auth/register`|AuthController.java|
|POST|`/api/v1/auth/login`|AuthController.java|
|POST|`/api/v1/auth/dev`|AuthController.java|
|POST|`/api/v1/auth/google`|AuthController.java|
|GET|`/api/v1/auth/options`|AuthController.java|
|GET|`/api/v1/auth/session`|AuthController.java|
|POST|`/api/v1/auth/logout`|AuthController.java|
|POST|`/api/v1/auth/resend-verification`|AuthController.java|
|POST|`/api/v1/auth/forgot-password`|AuthController.java|
|POST|`/api/v1/auth/verify-email`|AuthController.java|
|POST|`/api/v1/auth/reset-password`|AuthController.java|
|POST|`/api/v1/billing/checkout`|BillingController.java|
|POST|`/api/v1/billing/webhook`|BillingController.java|
|POST|`/api/v1/jobs`|JobController.java|
|GET|`/api/v1/jobs`|JobController.java|
|GET|`/api/v1/jobs/{id}`|JobController.java|
|PUT|`/api/v1/jobs/{id}/structured-jd`|JobController.java|
|POST|`/api/v1/analyses`|JobController.java|
|GET|`/api/v1/analyses`|JobController.java|
|GET|`/api/v1/analyses/{id}`|JobController.java|
|GET|`/api/v1/ai-jobs/{id}`|JobController.java|
|POST|`/api/v1/ai-jobs/{id}/retry`|JobController.java|
|POST|`/api/v1/resumes/text`|ResumeController.java|
|GET|`/api/v1/resumes`|ResumeController.java|
|GET|`/api/v1/resumes/{id}`|ResumeController.java|
|GET|`/api/v1/resumes/{id}/versions`|ResumeController.java|
|GET|`/api/v1/resume-versions/{id}`|ResumeController.java|
|PUT|`/api/v1/resume-versions/{id}/structured-content`|ResumeController.java|
|DELETE|`/api/v1/resumes/{id}`|ResumeController.java|
|POST|`/api/v1/tailoring-sessions`|TailoringController.java|
|GET|`/api/v1/tailoring-sessions/{id}`|TailoringController.java|
|PATCH|`/api/v1/resume-edits/{id}`|TailoringController.java|
|POST|`/api/v1/tailoring-sessions/{id}/exports`|JakeEnhancementController.java|
|POST|`/api/v1/tailoring-sessions/{id}/jake-exports`|JakeEnhancementController.java|
|GET|`/api/v1/exports`|TailoringController.java|
|GET|`/api/v1/exports/{id}/download`|TailoringController.java|
|GET|`/api/v1/users/me`|WorkspaceController.java|
|PUT|`/api/v1/users/me/account`|WorkspaceController.java|
|PUT|`/api/v1/users/me/profile`|WorkspaceController.java|
|GET|`/api/v1/dashboard/summary`|WorkspaceController.java|
|POST|`/api/v1/interview-questions/generate`|WorkspaceController.java|
|GET|`/api/v1/interview-questions`|WorkspaceController.java|
|POST|`/api/v1/interview-sessions`|WorkspaceController.java|
|GET|`/api/v1/interview-sessions`|WorkspaceController.java|
|GET|`/api/v1/interview-sessions/{id}`|WorkspaceController.java|
|POST|`/api/v1/interview-sessions/{id}/answers`|WorkspaceController.java|
|POST|`/api/v1/interview-sessions/{id}/complete`|WorkspaceController.java|
|POST|`/api/v1/applications`|WorkspaceController.java|
|GET|`/api/v1/applications`|WorkspaceController.java|
|GET|`/api/v1/applications/{id}`|WorkspaceController.java|
|PATCH|`/api/v1/applications/{id}`|WorkspaceController.java|
|DELETE|`/api/v1/applications/{id}`|WorkspaceController.java|
|POST|`/api/v1/reminders`|WorkspaceController.java|
|GET|`/api/v1/reminders`|WorkspaceController.java|
|PATCH|`/api/v1/reminders/{id}`|WorkspaceController.java|
|GET|`/api/v1/salary-research/capabilities`|SalaryMarketController.java|
|POST|`/api/v1/salary-research`|SalaryMarketController.java|
|POST|`/api/v1/salary-observations`|WorkspaceController.java|
|GET|`/api/v1/salary-observations`|WorkspaceController.java|
|POST|`/api/v1/salary-estimates`|WorkspaceController.java|
|GET|`/api/v1/salary-estimates`|WorkspaceController.java|
|GET|`/api/v1/insights`|WorkspaceController.java|
|GET|`/api/v1/offers/compare`|WorkspaceController.java|
|GET|`/api/v1/career-facts`|WorkspaceController.java|
|POST|`/api/v1/career-facts`|WorkspaceController.java|
|GET|`/api/v1/subscription`|WorkspaceController.java|
|GET|`/api/v1/privacy/data-export`|WorkspaceController.java|
|DELETE|`/api/v1/users/me`|WorkspaceController.java|


## Workflows added after the baseline

| Method and path (relative to /api/v1) | Contract |
|---|---|
| POST /auth/verify-email | Use `{email,code}` for six-digit codes. Existing verification links use `{token}`. |
| POST /users/me/link-google | Signed-in account with verified email. Accepts the current password and Google `credential`. |
| POST /users/me/setup-password | Existing Google identity and newly issued Google `credential`. Sets the supplied `password`. |
| POST /users/me/email-change | Request a code for `email` with current `password` or the linked Google `credential`. |
| POST /users/me/email-change/confirm | Apply the verified address with `{email,code}`. Signs out other sessions. |
| GET /resumes/archived | List archived resumes. |
| POST /resumes/{id}/restore | Restore an archived resume. |
| DELETE /resumes/{id}/permanent | Remove the resume and derived rows. Body `{"confirmation":"DELETE RESUME"}`. |
| PUT /resume-versions/{id}/structured-content | Confirm `structuredContent` and optional reviewed `contentText`. Returns the confirmed version's ID. |
| GET /jobs/{id}/analyses | Owned analysis history. Optional `page`. |
| GET /export-capabilities | Jake PDF availability from the startup rendering check. |
| POST /tailoring-sessions/{id}/exports | Creates Jake PDF or TEX using approved edits. The existing Jake route is an alias with shared idempotency. |
| GET /exports | Combined history with `template` and `downloadUrl` for each file. |
| GET /auth/options | Public Google OAuth Web Client ID, or an empty value when Google sign-in is unavailable. |
| POST /salary-research | Authenticated lookup with `{title,company,location,experienceFrom,experienceTo,currency}`. Returns the result status, qualifying evidence, related salary pages, and a status for each provider request. A combined estimate includes annual amount endpoints and pay basis. |
| GET /privacy/data-export/archive | ZIP with manifest and account documents. |
| POST /billing/portal | Open the linked Stripe customer's billing portal. |
| PUT /users/me/career-memory-preference | Set consent for opted-in AI context with `{enabled}`. |
| PATCH, DELETE /career-facts/{id} | Edit or delete an owned fact. |
| GET /notifications | Optional `limit` and zero-indexed `page`. |
| GET /resumes/page, /jobs/page, /applications/page, /notifications/page | Optional `size` and opaque `cursor`. Use the returned `nextCursor` to advance through `items`. |
| GET /resumes/page, /jobs/page, /applications/page | Optional `q` (up to 120 characters). Case-insensitive substring search of resume title or job company/title across the signed-in user's records before paging. Start with a fresh cursor when the search changes. Defaults are 30 resumes, 30 jobs, and 50 applications. |

Analysis, tailoring and export creation accept an `Idempotency-Key` header. Reuse the key after an unknown outcome. Reusing it with changed input returns 409. Keys are scoped per user and operation and retained for one day.

Use JSON strings for credentials, JSON booleans for settings and an ISO date for a salary observation. Salary amounts have up to 12 integer digits and 2 decimal places. Currency codes are normalized to three uppercase letters.
