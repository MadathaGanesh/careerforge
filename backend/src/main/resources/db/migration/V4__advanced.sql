CREATE TABLE career_facts (
 id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 fact_type TEXT NOT NULL,description TEXT NOT NULL,verified_by_user BOOLEAN NOT NULL DEFAULT false,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE reminders ADD COLUMN notified_at TIMESTAMPTZ;
CREATE TABLE billing_events (
 id TEXT PRIMARY KEY,provider TEXT NOT NULL,payload_type TEXT NOT NULL,
 received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

