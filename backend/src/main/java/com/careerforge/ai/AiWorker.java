package com.careerforge.ai;

import com.careerforge.core.ApiError;
import com.careerforge.core.Db;
import com.careerforge.documents.ReviewedEdits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

@Component
public class AiWorker {
    private static final Logger log = LoggerFactory.getLogger(AiWorker.class);
    private final Db db;
    private final CareerAi ai;
    private final TransactionTemplate tx;

    AiWorker(Db db, CareerAi ai, TransactionTemplate tx) {
        this.db = db;
        this.ai = ai;
        this.tx = tx;
    }

    private record TailoringDraft(String source, List<Map<String, Object>> edits) {}

    private record Claim(UUID id, UUID owner, UUID resource, String type, int attempt) {
        String table() {
            return switch (type) {
                case "ANALYSIS" -> "analyses";
                case "TAILORING" -> "tailoring_sessions";
                default -> throw new IllegalStateException("Unknown AI task");
            };
        }
    }

    @Scheduled(fixedDelayString = "${app.ai-poll-ms:2500}")
    public void work() {
        Claim job = tx.execute(status -> claim());
        if (job == null) return;
        try {
            tx.executeWithoutResult(status -> {
                if (ownsClaim(job)) setResourceStatus(job, "RUNNING");
            });
            if ("ANALYSIS".equals(job.type())) {
                var result = analyze(job.resource());
                publish(job, () -> db.jdbc.update("""
                        UPDATE analyses SET status='SUCCEEDED',score=?,result_json=?::jsonb,
                        model_id='fit-v3-local',rubric_version='fit-v3',completed_at=now() WHERE id=?
                        """, result.get("alignmentScore"), db.encode(result), job.resource()));
            } else {
                var draft = tailor(job);
                String hash = ReviewedEdits.fingerprint(draft.source());
                publish(job, () -> {
                    db.jdbc.update("DELETE FROM edit_proposals WHERE session_id=?", job.resource());
                    for (var edit : draft.edits()) {
                        String source = (String) edit.get("originalText");
                        int start = draft.source().indexOf(source);
                        if (start < 0) throw ApiError.invalid("Generated edit does not have a verified source location");
                        db.jdbc.update("""
                                INSERT INTO edit_proposals(id,session_id,original_text,proposed_text,reason,evidence,risk,source_start,source_end,source_sha256)
                                VALUES (?,?,?,?,?,?,?,?,?,?)
                                """, UUID.randomUUID(), job.resource(), edit.get("originalText"), edit.get("proposedText"),
                                edit.get("reason"), edit.get("evidence"), edit.get("risk"), start, start + source.length(), hash);
                    }
                    db.jdbc.update("UPDATE tailoring_sessions SET status='SUCCEEDED',model_id=? WHERE id=?",
                            ai.modelId(), job.resource());
                });
            }
        } catch (RuntimeException e) {
            String code = e instanceof ApiError error ? error.code : "PROCESSING_FAILED";
            log.warn("AI task failed id={} attempt={} code={}", job.id(), job.attempt(), code);
            tx.executeWithoutResult(status -> fail(job, code));
        }
    }

    private Claim claim() {
        for (var row : db.list("""
                UPDATE ai_jobs SET status='FAILED',error_code='LEASE_EXPIRED',completed_at=now(),lease_until=NULL
                WHERE status='RUNNING' AND lease_until<now() AND attempts>=3 RETURNING *
                """)) {
            setResourceStatus(toClaim(row), "FAILED");
        }
        var rows = db.list("""
                UPDATE ai_jobs SET status='RUNNING',attempts=attempts+1,lease_until=now()+interval '2 minutes'
                WHERE id=(SELECT id FROM ai_jobs WHERE ((status='QUEUED' AND next_run_at<=now())
                    OR (status='RUNNING' AND lease_until<now())) AND attempts<3
                    ORDER BY next_run_at,created_at FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING *
                """);
        return rows.isEmpty() ? null : toClaim(rows.getFirst());
    }

    private Claim toClaim(Map<String, Object> row) {
        return new Claim((UUID) row.get("id"), (UUID) row.get("userId"), (UUID) row.get("resourceId"),
                (String) row.get("taskType"), (Integer) row.get("attempts"));
    }

    private boolean ownsClaim(Claim job) {
        return !db.list("""
                SELECT id FROM ai_jobs WHERE id=? AND attempts=? AND status='RUNNING' AND lease_until>now()
                FOR UPDATE
                """, job.id(), job.attempt()).isEmpty();
    }

    private void publish(Claim job, Runnable result) {
        tx.executeWithoutResult(status -> {
            if (!ownsClaim(job)) return;
            result.run();
            db.jdbc.update("""
                    UPDATE ai_jobs SET status='SUCCEEDED',completed_at=now(),lease_until=NULL,error_code=NULL WHERE id=?
                    """, job.id());
        });
    }

    private void fail(Claim job, String code) {
        if (!ownsClaim(job)) return;
        boolean retry = job.attempt() < 3 && Set.of("GEMINI_TRANSIENT", "GEMINI_TIMEOUT").contains(code);
        String state = retry ? "QUEUED" : "FAILED";
        db.jdbc.update("""
                UPDATE ai_jobs SET status=?,error_code=?,lease_until=NULL,
                next_run_at=now()+make_interval(secs => ?),
                completed_at=CASE WHEN ? THEN NULL ELSE now() END WHERE id=?
                """, state, code, retry ? 5 * (1 << job.attempt()) : 0, retry, job.id());
        setResourceStatus(job, state);
    }

    private void setResourceStatus(Claim job, String state) {
        db.jdbc.update("UPDATE " + job.table() + " SET status=? WHERE id=?", state, job.resource());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> analyze(UUID id) {
        var record = db.one("""
                SELECT COALESCE(a.jd_snapshot,j.extracted_json) AS extracted_json,v.content_text
                FROM analyses a JOIN jobs j ON j.id=a.job_id JOIN resume_versions v ON v.id=a.resume_version_id
                WHERE a.id=?
                """, id);
        return ai.fit((Map<String, Object>) record.get("extractedJson"), (String) record.get("contentText"));
    }

    private TailoringDraft tailor(Claim job) {
        var row = db.one("""
                SELECT j.raw_jd,v.content_text FROM tailoring_sessions t JOIN analyses a ON a.id=t.analysis_id
                JOIN jobs j ON j.id=a.job_id JOIN resume_versions v ON v.id=t.source_version_id WHERE t.id=?
                """, job.resource());
        String source = (String) row.get("contentText");
        return new TailoringDraft(source, ai.edits(source, (String) row.get("rawJd"), ai.careerMemory(job.owner())));
    }
}
