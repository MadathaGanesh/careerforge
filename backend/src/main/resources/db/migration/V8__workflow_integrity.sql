ALTER TABLE interview_answers DROP CONSTRAINT interview_answers_question_id_fkey;
ALTER TABLE interview_answers ADD CONSTRAINT interview_answers_question_id_fkey
    FOREIGN KEY (question_id) REFERENCES interview_questions(id) ON DELETE CASCADE;
CREATE INDEX idx_interview_answers_question ON interview_answers(question_id);

ALTER TABLE privacy_requests ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE privacy_requests DROP CONSTRAINT privacy_requests_user_id_fkey;
ALTER TABLE privacy_requests ADD CONSTRAINT privacy_requests_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE SET NULL;
ALTER TABLE privacy_requests ADD COLUMN cleanup_subject UUID;
ALTER TABLE privacy_requests ADD COLUMN cleanup_keys JSONB;
CREATE INDEX idx_privacy_pending ON privacy_requests(created_at) WHERE status = 'PENDING';

CREATE TABLE auth_rate_limits (
    bucket_key TEXT NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL,
    PRIMARY KEY (bucket_key, window_start)
);
CREATE INDEX idx_auth_rate_limit_expiry ON auth_rate_limits(window_start);
ALTER TABLE auth_tokens ADD COLUMN target_email VARCHAR(320);

ALTER TABLE resume_versions ADD COLUMN confirmed_at TIMESTAMPTZ;
ALTER TABLE analyses ADD COLUMN jd_snapshot JSONB;
ALTER TABLE ai_jobs ADD COLUMN next_run_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX idx_ai_jobs_resource ON ai_jobs(user_id, task_type, resource_id);
CREATE INDEX idx_usage_events_bucket ON usage_events(user_id,event_type,created_at);

CREATE TABLE idempotent_requests (
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    operation TEXT NOT NULL,
    request_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    response JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id,operation,request_key)
);

ALTER TABLE privacy_requests ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE documents DROP CONSTRAINT documents_resume_version_id_fkey;
ALTER TABLE documents ADD FOREIGN KEY (resume_version_id) REFERENCES resume_versions(id) ON DELETE CASCADE;
ALTER TABLE analyses DROP CONSTRAINT analyses_resume_version_id_fkey;
ALTER TABLE analyses ADD FOREIGN KEY (resume_version_id) REFERENCES resume_versions(id) ON DELETE CASCADE;
ALTER TABLE tailoring_sessions DROP CONSTRAINT tailoring_sessions_analysis_id_fkey;
ALTER TABLE tailoring_sessions ADD FOREIGN KEY (analysis_id) REFERENCES analyses(id) ON DELETE CASCADE;
ALTER TABLE tailoring_sessions DROP CONSTRAINT tailoring_sessions_source_version_id_fkey;
ALTER TABLE tailoring_sessions ADD FOREIGN KEY (source_version_id) REFERENCES resume_versions(id) ON DELETE CASCADE;
ALTER TABLE exports DROP CONSTRAINT exports_session_id_fkey;
ALTER TABLE exports ADD FOREIGN KEY (session_id) REFERENCES tailoring_sessions(id) ON DELETE CASCADE;
ALTER TABLE exports DROP CONSTRAINT exports_resume_version_id_fkey;
ALTER TABLE exports ADD FOREIGN KEY (resume_version_id) REFERENCES resume_versions(id) ON DELETE CASCADE;
ALTER TABLE applications DROP CONSTRAINT applications_resume_version_id_fkey;
ALTER TABLE applications ADD FOREIGN KEY (resume_version_id) REFERENCES resume_versions(id) ON DELETE SET NULL;
ALTER TABLE tailoring_sessions ADD COLUMN model_id TEXT;
ALTER TABLE tailoring_sessions ADD COLUMN prompt_version TEXT NOT NULL DEFAULT 'tailor-v2';

ALTER TABLE subscriptions ADD COLUMN provider_customer TEXT;
ALTER TABLE subscriptions ADD COLUMN provider_checked_at TIMESTAMPTZ;
ALTER TABLE subscriptions ADD COLUMN checkout_key UUID;
ALTER TABLE subscriptions ADD COLUMN checkout_started_at TIMESTAMPTZ;
ALTER TABLE subscriptions ADD COLUMN checkout_session TEXT;
ALTER TABLE subscriptions ADD COLUMN checkout_url TEXT;
ALTER TABLE subscriptions ADD COLUMN checkout_expires_at TIMESTAMPTZ;
CREATE INDEX idx_subscriptions_provider_ref ON subscriptions(provider_ref) WHERE provider_ref IS NOT NULL;
UPDATE subscriptions SET status='CANCELED' WHERE status='CANCELLED';

CREATE INDEX idx_analyses_job_history ON analyses(user_id,job_id,created_at DESC,id DESC);
CREATE INDEX idx_tailoring_source_version ON tailoring_sessions(source_version_id);
CREATE INDEX idx_documents_resume_version ON documents(resume_version_id);
CREATE INDEX idx_exports_resume_version ON exports(resume_version_id);
CREATE INDEX idx_jake_exports_resume_version ON jake_resume_exports(resume_version_id);
CREATE INDEX idx_auth_tokens_expiry ON auth_tokens(expires_at);
