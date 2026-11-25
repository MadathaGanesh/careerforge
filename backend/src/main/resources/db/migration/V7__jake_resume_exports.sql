CREATE TABLE jake_resume_exports
(
    id                   UUID PRIMARY KEY,
    user_id              UUID        NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    tailoring_session_id UUID        NOT NULL REFERENCES tailoring_sessions (id) ON DELETE CASCADE,
    resume_version_id    UUID        NOT NULL REFERENCES resume_versions (id) ON DELETE CASCADE,
    format               VARCHAR(8)  NOT NULL CHECK (format IN ('PDF', 'TEX')),
    storage_key          TEXT        NOT NULL UNIQUE,
    filename             TEXT        NOT NULL,
    sha256               VARCHAR(64) NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_jake_exports_owner ON jake_resume_exports (user_id, created_at DESC);
CREATE INDEX idx_jake_exports_session ON jake_resume_exports (tailoring_session_id, created_at DESC);
