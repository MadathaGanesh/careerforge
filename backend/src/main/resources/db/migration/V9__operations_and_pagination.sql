CREATE INDEX idx_exports_storage_key ON exports(storage_key);
CREATE INDEX idx_notifications_owner_time ON user_notifications(user_id,created_at DESC,id DESC);
CREATE INDEX idx_jobs_owner_time ON jobs(user_id,created_at DESC,id DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_resumes_owner_time ON resumes(user_id,created_at DESC,id DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_applications_owner_time ON applications(user_id,updated_at DESC,id DESC);
CREATE INDEX idx_ai_jobs_due ON ai_jobs(next_run_at,created_at) WHERE status='QUEUED';
CREATE INDEX idx_ai_jobs_lease ON ai_jobs(lease_until) WHERE status='RUNNING';
CREATE INDEX idx_privacy_due ON privacy_requests(next_attempt_at,created_at) WHERE status='PENDING';

ALTER TABLE edit_proposals ADD COLUMN source_start INTEGER;
ALTER TABLE edit_proposals ADD COLUMN source_end INTEGER;
ALTER TABLE edit_proposals ADD COLUMN source_sha256 VARCHAR(64);
ALTER TABLE edit_proposals ADD CONSTRAINT proposal_source_coordinates
CHECK ((source_start IS NULL AND source_end IS NULL AND source_sha256 IS NULL) OR
       (source_start IS NOT NULL AND source_end IS NOT NULL AND source_sha256 IS NOT NULL
        AND source_start >= 0 AND source_end > source_start));
