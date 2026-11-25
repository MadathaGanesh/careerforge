CREATE TABLE interview_questions (
 id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 job_id UUID NOT NULL REFERENCES jobs(id), category TEXT NOT NULL,
 question TEXT NOT NULL, suggested_answer TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE interview_sessions (
 id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 job_id UUID REFERENCES jobs(id), status TEXT NOT NULL DEFAULT 'IN_PROGRESS',
 overall_score NUMERIC(5,2),created_at TIMESTAMPTZ NOT NULL DEFAULT now(),completed_at TIMESTAMPTZ
);
CREATE TABLE interview_answers (
 id UUID PRIMARY KEY,session_id UUID NOT NULL REFERENCES interview_sessions(id) ON DELETE CASCADE,
 question_id UUID NOT NULL REFERENCES interview_questions(id), answer TEXT NOT NULL,
 feedback_json JSONB NOT NULL DEFAULT '{}',score NUMERIC(5,2),created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(session_id, question_id)
);
CREATE TABLE applications (
 id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 job_id UUID NOT NULL REFERENCES jobs(id),resume_version_id UUID REFERENCES resume_versions(id),
 status TEXT NOT NULL DEFAULT 'SAVED', salary_offered TEXT NOT NULL DEFAULT '',
 notes TEXT NOT NULL DEFAULT '', applied_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_applications_owner ON applications(user_id,status);
CREATE TABLE application_events (
 id UUID PRIMARY KEY,application_id UUID NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
 old_status TEXT,new_status TEXT NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE reminders (
 id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 application_id UUID REFERENCES applications(id) ON DELETE CASCADE,
 title TEXT NOT NULL,due_at TIMESTAMPTZ NOT NULL,completed BOOLEAN NOT NULL DEFAULT false,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE salary_observations (
 id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 role TEXT NOT NULL,location TEXT NOT NULL,experience_years NUMERIC(5,2),
 low_value NUMERIC(14,2) NOT NULL,high_value NUMERIC(14,2) NOT NULL,
 currency CHAR(3) NOT NULL DEFAULT 'INR',compensation_type TEXT NOT NULL DEFAULT 'ANNUAL_CTC',
 source_label TEXT NOT NULL,source_url TEXT,observed_at DATE NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),CHECK(high_value>=low_value)
);
CREATE TABLE salary_estimates (
 id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 job_id UUID REFERENCES jobs(id),result_json JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

