-- Run with a restricted operations account. Every result omits resume, JD, identity and payment data.
SELECT task_type,status,count(*) AS jobs,
       min(created_at) FILTER (WHERE status IN ('QUEUED','RUNNING')) AS oldest_open
FROM ai_jobs GROUP BY task_type,status ORDER BY task_type,status;

SELECT id,task_type,error_code,attempts,completed_at
FROM ai_jobs WHERE status='FAILED' ORDER BY completed_at DESC NULLS LAST LIMIT 50;

SELECT status,count(*) AS emails,min(next_attempt_at) AS oldest_due
FROM notification_email_outbox WHERE status IN ('PENDING','FAILED','SENDING') GROUP BY status;

SELECT kind,status,count(*) AS cleanup_count, min(next_attempt_at) AS oldest_due
FROM privacy_requests WHERE status='PENDING' GROUP BY kind,status;

SELECT status,count(*) AS subscriptions,min(provider_checked_at) AS oldest_provider_check
FROM subscriptions WHERE provider IS NOT NULL GROUP BY status;
