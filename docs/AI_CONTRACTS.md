# CareerForge runtime AI contracts

The draft specifications are in `docs/specifications`. The release contracts live in `backend/src/main/resources/contracts`. The backend loads them at runtime. CI checks the generated TypeScript types for drift with `python3 scripts/update-ai-schemas.py --check`.

| Contract | Runtime behavior |
|---|---|
| `resume-extract-v1` | Bounds the candidate, skill, experience, and warning fields. Confirmation versions the reviewed text. Consumers use that reviewed version. |
| `job-extract-v1` | Validates IDs, labels, categories, priorities, source quotes, and experience. Requirement quotes are checked against the actual saved posting. |
| `fit-v3` | Weights mandatory requirements 35, experience 25, responsibilities 20, preferred 10, and education/certification 10. It renormalizes weights when categories are absent. |
| `tailoring-proposals-v2` | Limits proposal fields and count. Proposals require a unique source match and newly introduced figures must be supported. The application records source offsets and a SHA-256 fingerprint. |
| `interview-questions-v2` | Validates the allowed question categories, length, and response structure. |
| `interview-feedback-v2` | Bounded feedback with a 0–100 score in configured mode. Local mode supplies a practice framework and the UI displays “Not assessed.” |

Fit v3 uses deterministic evidence. A direct mention or a recognized alias can yield demonstrated evidence. Partial text evidence or shorter stated experience yields partial credit. Negated claims earn no credit. Study or exposure remains unverified. The response includes exact excerpts and UTF-16 offsets, category exclusions and a score explanation. This score estimates alignment and cannot verify claimed qualifications or predict hiring outcomes.

The application requests a structured provider response, checks it against the runtime schema, and separately checks domain rules. User content is in the data message, separate from system instructions. Provider requests have bounded content, output tokens, and concurrent capacity. Provider tests use fixtures for schema rejection, unsupported numerical claims, 429, 5xx, and timeout handling. A real configured Gemini evaluation remains part of staging.

Existing stored analysis results remain readable. Newly generated results use v3 and preserve the job and resume version used at evaluation time.
