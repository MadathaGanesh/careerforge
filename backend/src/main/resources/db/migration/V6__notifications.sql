CREATE TABLE user_notifications
(
    id         UUID PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    kind       VARCHAR(50)  NOT NULL,
    title      VARCHAR(200) NOT NULL,
    body       TEXT         NOT NULL,
    target_url TEXT         NOT NULL DEFAULT '/dashboard',
    event_key  VARCHAR(250) NOT NULL,
    is_read    BOOLEAN      NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    read_at    TIMESTAMPTZ,
    CONSTRAINT uq_notification_event UNIQUE (user_id, event_key)
);
CREATE INDEX idx_notifications_inbox ON user_notifications (user_id, is_read, created_at DESC);

CREATE TABLE notification_email_outbox
(
    id              UUID PRIMARY KEY,
    notification_id UUID        NOT NULL UNIQUE REFERENCES user_notifications (id) ON DELETE CASCADE,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_until     TIMESTAMPTZ,
    sent_at         TIMESTAMPTZ,
    last_error      VARCHAR(200),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notification_email_due ON notification_email_outbox (status, next_attempt_at);

CREATE TABLE password_change_failures
(
    id           UUID PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_password_failures_user ON password_change_failures (user_id, attempted_at DESC);

-- Access granted deliberately through database administration, not a client flag.
CREATE TABLE notification_admins
(
    user_id    UUID PRIMARY KEY REFERENCES app_users (id) ON DELETE CASCADE,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

