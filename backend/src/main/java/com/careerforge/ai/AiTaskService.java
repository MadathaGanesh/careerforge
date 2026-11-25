package com.careerforge.ai;

import com.careerforge.core.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class AiTaskService {
    public record AnalysisCreated(UUID analysisId, UUID aiJobId, String status) {}
    public record TailoringCreated(UUID sessionId, UUID aiJobId, String status) {}

    private final Db db;
    private final Quota quota;
    private final Idempotency idempotency;

    public AiTaskService(Db db, Quota quota, Idempotency idempotency) {
        this.db = db;
        this.quota = quota;
        this.idempotency = idempotency;
    }

    @Transactional
    public AnalysisCreated analyze(UUID jobId, UUID version, String key) {
        var response = idempotency.run("ANALYSIS", key, jobId + ":" + version, () -> {
            quota.consume("ANALYSIS");
            var job = db.one("SELECT extracted_json FROM jobs WHERE id=? AND user_id=? AND deleted_at IS NULL", jobId, db.uid());
            var reviewed = db.one("""
                    SELECT v.confirmed_at FROM resume_versions v JOIN resumes r ON r.id=v.resume_id
                    WHERE v.id=? AND r.user_id=? AND r.deleted_at IS NULL
                    """, version, db.uid());
            if (reviewed.get("confirmedAt") == null) throw ApiError.conflict("Review and confirm the resume version first");
            UUID id = UUID.randomUUID(), task = UUID.randomUUID();
            db.jdbc.update("""
                    INSERT INTO analyses(id,user_id,job_id,resume_version_id,status,jd_snapshot,rubric_version)
                    VALUES (?,?,?,?,'QUEUED',?::jsonb,'fit-v3')
                    """, id, db.uid(), jobId, version, db.encode(job.get("extractedJson")));
            db.jdbc.update("INSERT INTO ai_jobs(id,user_id,task_type,resource_id) VALUES (?,?,'ANALYSIS',?)", task, db.uid(), id);
            return Map.of("analysisId", id, "aiJobId", task, "status", "QUEUED");
        });
        return db.json.convertValue(response, AnalysisCreated.class);
    }

    @Transactional
    public TailoringCreated tailor(UUID analysis, String key) {
        var response = idempotency.run("TAILORING", key, analysis.toString(), () -> {
            quota.consume("TAILORING");
            var a = db.one("""
                    SELECT a.status,a.resume_version_id FROM analyses a JOIN resume_versions v ON v.id=a.resume_version_id
                    JOIN resumes r ON r.id=v.resume_id WHERE a.id=? AND a.user_id=? AND r.deleted_at IS NULL
                    """, analysis, db.uid());
            if (!"SUCCEEDED".equals(a.get("status"))) throw ApiError.conflict("Analysis must succeed before tailoring");
            UUID id = UUID.randomUUID(), task = UUID.randomUUID();
            db.jdbc.update("INSERT INTO tailoring_sessions(id,user_id,analysis_id,source_version_id) VALUES (?,?,?,?)",
                    id, db.uid(), analysis, a.get("resumeVersionId"));
            db.jdbc.update("INSERT INTO ai_jobs(id,user_id,task_type,resource_id) VALUES (?,?,'TAILORING',?)", task, db.uid(), id);
            return Map.of("sessionId", id, "aiJobId", task, "status", "QUEUED");
        });
        return db.json.convertValue(response, TailoringCreated.class);
    }
}
