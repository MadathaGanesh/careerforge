package com.careerforge.core;

import com.careerforge.documents.DocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

@Service
public class PrivacyService {
    private static final Logger log = LoggerFactory.getLogger(PrivacyService.class);
    private final Db db;
    private final DocumentService docs;
    private final TransactionTemplate tx;
    private final StripeSubscriptionSync billing;

    public PrivacyService(Db db, DocumentService docs, TransactionTemplate tx, StripeSubscriptionSync billing) {
        this.db = db;
        this.docs = docs;
        this.tx = tx;
        this.billing = billing;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> snapshot(UUID owner) {
        var data = new LinkedHashMap<String, Object>();
        data.put("schemaVersion", 1);
        data.put("account", db.one("SELECT id,email,full_name,email_verified,created_at FROM app_users WHERE id=?", owner));
        data.put("profile", db.one("SELECT * FROM user_profiles WHERE user_id=?", owner));
        data.put("identityProviders", db.list("SELECT provider FROM auth_identities WHERE user_id=?", owner));
        data.put("resumes", owned("resumes", owner));
        data.put("resumeVersions", db.list("SELECT v.* FROM resume_versions v JOIN resumes r ON r.id=v.resume_id WHERE r.user_id=? ORDER BY v.resume_id,v.version_number", owner));
        data.put("documents", db.list("SELECT id,resume_version_id,kind,media_type,filename,size_bytes,sha256,created_at FROM documents WHERE user_id=?", owner));
        data.put("jobs", owned("jobs", owner));
        data.put("analyses", owned("analyses", owner));
        data.put("tailoringSessions", owned("tailoring_sessions", owner));
        data.put("editProposals", db.list("SELECT e.* FROM edit_proposals e JOIN tailoring_sessions s ON s.id=e.session_id WHERE s.user_id=?", owner));
        data.put("exports", db.list("SELECT id,session_id,resume_version_id,format,filename,created_at FROM exports WHERE user_id=?", owner));
        data.put("jakeExports", db.list("SELECT id,tailoring_session_id,resume_version_id,format,filename,sha256,created_at FROM jake_resume_exports WHERE user_id=?", owner));
        data.put("interviewQuestions", owned("interview_questions", owner));
        data.put("interviews", owned("interview_sessions", owner));
        data.put("interviewAnswers", db.list("SELECT a.* FROM interview_answers a JOIN interview_sessions s ON s.id=a.session_id WHERE s.user_id=?", owner));
        data.put("applications", owned("applications", owner));
        data.put("applicationEvents", db.list("SELECT e.* FROM application_events e JOIN applications a ON a.id=e.application_id WHERE a.user_id=?", owner));
        data.put("reminders", owned("reminders", owner));
        data.put("notifications", owned("user_notifications", owner));
        data.put("salaryObservations", owned("salary_observations", owner));
        data.put("salaryEstimates", owned("salary_estimates", owner));
        data.put("careerFacts", owned("career_facts", owner));
        data.put("usage", owned("usage_events", owner));
        data.put("subscription", db.one("SELECT plan,status,period_end,updated_at FROM subscriptions WHERE user_id=?", owner));
        data.put("audit", owned("audit_events", owner));
        return data;
    }

    private List<Map<String, Object>> owned(String table, UUID owner) {
        return db.list("SELECT * FROM " + table + " WHERE user_id=?", owner);
    }

    public List<Map<String, Object>> files(UUID owner) {
        return db.list("""
                SELECT id, filename, storage_key, 'originals' AS folder FROM documents WHERE user_id=?
                UNION ALL SELECT id,filename,storage_key,'exports' FROM exports WHERE user_id=?
                UNION ALL SELECT id,filename,storage_key,'jake-exports' FROM jake_resume_exports WHERE user_id=?
                """, owner, owner, owner);
    }

    @Transactional
    public void deleteAccount(UUID owner) {
        db.lockUser(owner);
        billing.prepareDeletion(owner);
        var plan = billing.plan(owner);
        if (billing.activeLink(plan) || plan.get("checkoutKey") != null) {
            throw ApiError.conflict("Cancel your subscription or wait for checkout to expire before deleting your account.");
        }
        enqueue(owner, "DELETE_ACCOUNT", null);
        // Workers lock the queue row before writing the result. Delete in the same order.
        db.jdbc.update("DELETE FROM ai_jobs WHERE user_id=?", owner);
        db.jdbc.update("DELETE FROM app_users WHERE id=?", owner);
    }

    @Transactional
    public void deleteResume(UUID owner, UUID id) {
        db.lockUser(owner);
        db.one("SELECT id FROM resumes WHERE id=? AND user_id=?", id, owner);
        var files = db.list("""
                SELECT d.storage_key FROM documents d JOIN resume_versions v ON v.id=d.resume_version_id WHERE v.resume_id=?
                UNION ALL SELECT e.storage_key FROM exports e JOIN resume_versions v ON v.id=e.resume_version_id WHERE v.resume_id=?
                UNION ALL SELECT e.storage_key FROM jake_resume_exports e JOIN resume_versions v ON v.id=e.resume_version_id WHERE v.resume_id=?
                """, id, id, id).stream().map(row -> row.get("storageKey")).toList();
        enqueue(owner, "DELETE_RESUME", files);
        db.jdbc.update("""
                DELETE FROM ai_jobs q WHERE q.user_id=? AND (
                    (q.task_type='ANALYSIS' AND q.resource_id IN (
                        SELECT a.id FROM analyses a JOIN resume_versions v ON v.id=a.resume_version_id WHERE v.resume_id=?))
                    OR (q.task_type='TAILORING' AND q.resource_id IN (
                        SELECT s.id FROM tailoring_sessions s JOIN resume_versions v ON v.id=s.source_version_id WHERE v.resume_id=?)))
                """, owner, id, id);
        db.jdbc.update("DELETE FROM idempotent_requests WHERE user_id=?", owner);
        db.jdbc.update("DELETE FROM resumes WHERE id=?", id);
    }

    private void enqueue(UUID owner, String kind, List<?> keys) {
        db.jdbc.update("""
                INSERT INTO privacy_requests(id,user_id,kind,cleanup_subject,cleanup_keys)
                VALUES (?,?,?,?,?::jsonb)
                """, UUID.randomUUID(), owner, kind, owner, keys == null ? null : db.encode(keys));
    }

    @Scheduled(fixedDelayString = "${app.privacy-poll-ms:15000}")
    public void runCleanup() {
        for (var task : db.list("""
                SELECT id FROM privacy_requests WHERE status='PENDING' AND cleanup_subject IS NOT NULL
                AND next_attempt_at<=now() ORDER BY created_at LIMIT 20
                """)) {
            try {
                tx.executeWithoutResult(status -> clean((UUID) task.get("id")));
            } catch (RuntimeException e) {
                log.warn("Privacy cleanup failed request={} category={}", task.get("id"), e.getClass().getSimpleName());
                db.jdbc.update("""
                        UPDATE privacy_requests SET next_attempt_at=now()+interval '5 minutes'
                        WHERE id=? AND status='PENDING'
                        """, task.get("id"));
            }
        }
    }

    private void clean(UUID id) {
        var rows = db.list("SELECT * FROM privacy_requests WHERE id=? AND status='PENDING' FOR UPDATE SKIP LOCKED", id);
        if (rows.isEmpty()) return;
        var row = rows.getFirst();
        UUID owner = (UUID) row.get("cleanupSubject");
        if ("DELETE_ACCOUNT".equals(row.get("kind"))) docs.purge(owner);
        if (row.get("cleanupKeys") instanceof List<?> files) files.forEach(file -> docs.remove(String.valueOf(file), owner));
        db.jdbc.update("""
                UPDATE privacy_requests SET status='COMPLETED',completed_at=now(),cleanup_subject=NULL,cleanup_keys=NULL
                WHERE id=?
                """, id);
    }
}
