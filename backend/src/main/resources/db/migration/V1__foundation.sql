CREATE TABLE app_users (
 id UUID PRIMARY KEY, email VARCHAR(320) NOT NULL UNIQUE, full_name VARCHAR(200) NOT NULL,
 password_hash TEXT, email_verified BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), deleted_at TIMESTAMPTZ
);
CREATE TABLE auth_identities (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 provider VARCHAR(30) NOT NULL, provider_subject TEXT NOT NULL,
 UNIQUE(provider,provider_subject)
);
CREATE TABLE auth_tokens (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 kind VARCHAR(30) NOT NULL, token_hash TEXT NOT NULL UNIQUE, expires_at TIMESTAMPTZ NOT NULL, used_at TIMESTAMPTZ
);
CREATE TABLE user_profiles (
 user_id UUID PRIMARY KEY REFERENCES app_users(id) ON DELETE CASCADE,
 headline TEXT NOT NULL DEFAULT '', years_experience NUMERIC(5,2) NOT NULL DEFAULT 0,
 location TEXT NOT NULL DEFAULT '', target_roles JSONB NOT NULL DEFAULT '[]',
 skills JSONB NOT NULL DEFAULT '[]', preferences JSONB NOT NULL DEFAULT '{}',
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE resumes (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 title TEXT NOT NULL, original_filename TEXT, original_text TEXT NOT NULL,
 content_type TEXT NOT NULL, structured_json JSONB NOT NULL DEFAULT '{}',
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), deleted_at TIMESTAMPTZ
);
CREATE INDEX idx_resumes_user ON resumes(user_id,created_at DESC);
CREATE TABLE resume_versions (
 id UUID PRIMARY KEY, resume_id UUID NOT NULL REFERENCES resumes(id) ON DELETE CASCADE,
 version_number INTEGER NOT NULL CHECK(version_number>0), content_text TEXT NOT NULL,
 content_json JSONB NOT NULL DEFAULT '{}', parent_version_id UUID REFERENCES resume_versions(id),
 source_job_id UUID, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(resume_id,version_number)
);
CREATE TABLE documents (
 id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 resume_version_id UUID REFERENCES resume_versions(id), kind TEXT NOT NULL,
 storage_key TEXT NOT NULL UNIQUE, media_type TEXT NOT NULL, filename TEXT NOT NULL,
 size_bytes BIGINT NOT NULL, sha256 TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE jobs (
 id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 company_name TEXT NOT NULL DEFAULT '', title TEXT NOT NULL DEFAULT '',
 location TEXT NOT NULL DEFAULT '', salary_disclosed TEXT NOT NULL DEFAULT '',
 job_url TEXT, raw_jd TEXT NOT NULL, extracted_json JSONB NOT NULL DEFAULT '{}',
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), deleted_at TIMESTAMPTZ
);
CREATE INDEX idx_jobs_user ON jobs(user_id,created_at DESC);
CREATE TABLE analyses (
 id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 job_id UUID NOT NULL REFERENCES jobs(id),resume_version_id UUID NOT NULL REFERENCES resume_versions(id),
 status TEXT NOT NULL DEFAULT 'QUEUED', score NUMERIC(5,2), result_json JSONB NOT NULL DEFAULT '{}',
 rubric_version TEXT NOT NULL DEFAULT 'fit-v1', model_id TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),completed_at TIMESTAMPTZ
);
CREATE INDEX idx_analyses_user ON analyses(user_id,created_at DESC);
CREATE TABLE tailoring_sessions (
 id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 analysis_id UUID NOT NULL REFERENCES analyses(id), source_version_id UUID NOT NULL REFERENCES resume_versions(id),
 status TEXT NOT NULL DEFAULT 'QUEUED',created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE edit_proposals (
 id UUID PRIMARY KEY,session_id UUID NOT NULL REFERENCES tailoring_sessions(id) ON DELETE CASCADE,
 original_text TEXT NOT NULL,proposed_text TEXT NOT NULL,reason TEXT NOT NULL,
 evidence TEXT NOT NULL DEFAULT '',risk TEXT NOT NULL DEFAULT 'REQUIRES_CONFIRMATION',
 decision TEXT NOT NULL DEFAULT 'PENDING',revision INTEGER NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE exports (
 id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 session_id UUID REFERENCES tailoring_sessions(id),resume_version_id UUID NOT NULL REFERENCES resume_versions(id),
 format TEXT NOT NULL CHECK(format IN ('PDF','DOCX')),
 storage_key TEXT NOT NULL,filename TEXT NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE ai_jobs (
 id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 task_type TEXT NOT NULL, resource_id UUID NOT NULL,status TEXT NOT NULL DEFAULT 'QUEUED',
 attempts INTEGER NOT NULL DEFAULT 0,error_code TEXT,lease_until TIMESTAMPTZ,created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 completed_at TIMESTAMPTZ
);
CREATE INDEX idx_ai_jobs_queue ON ai_jobs(status,created_at);

