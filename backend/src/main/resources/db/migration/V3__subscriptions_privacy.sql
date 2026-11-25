CREATE TABLE subscriptions (
 user_id UUID PRIMARY KEY REFERENCES app_users(id) ON DELETE CASCADE,
 plan TEXT NOT NULL DEFAULT 'FREE',status TEXT NOT NULL DEFAULT 'ACTIVE',
 provider TEXT,provider_ref TEXT, period_end TIMESTAMPTZ, updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE usage_events (
 id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 event_type TEXT NOT NULL,model_id TEXT,input_tokens INTEGER,output_tokens INTEGER,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE audit_events (
 id UUID PRIMARY KEY,user_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
 event_type TEXT NOT NULL,resource_type TEXT,resource_id UUID,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE privacy_requests (
 id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
 kind TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'PENDING',
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), completed_at TIMESTAMPTZ
);

