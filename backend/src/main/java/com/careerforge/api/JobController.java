package com.careerforge.api;

import com.careerforge.ai.CareerAi;
import com.careerforge.ai.AiContracts;
import com.careerforge.ai.AiTaskService;
import com.careerforge.core.CursorPage;
import jakarta.validation.Valid;
import com.careerforge.core.ApiError;
import com.careerforge.core.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class JobController {
    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    private final Db db;
    private final CareerAi ai;
    private final AiTaskService tasks;

    JobController(Db db, CareerAi ai, AiTaskService tasks) {
        this.db = db;
        this.ai = ai;
        this.tasks = tasks;
    }

    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/jobs")
    public ResponseEntity<Map<String, Object>> addJob(@Valid @RequestBody WorkflowRequests.DraftJob body) {
        log.debug("Entering JobController.addJob");
        String jd = body.rawJd().strip(), title = value(body.title());
        if (jd.length() < 80 || jd.length() > 50000) throw ApiError.invalid("JD must contain 80–50,000 characters");
        UUID id = UUID.randomUUID(), user = db.uid();
        Map<String, Object> extracted = ai.job(jd, title);
        db.jdbc.update("INSERT INTO jobs(id,user_id,company_name,title,location,salary_disclosed,job_url,raw_jd,extracted_json) VALUES (?,?,?,?,?,?,?,?,?::jsonb)", id, user, value(body.companyName()), title, value(body.location()), value(body.salaryDisclosed()), value(body.jobUrl()), jd, db.encode(extracted));
        db.audit("JOB_SAVED", "job", id);
        return ResponseEntity.status(201).body(Map.of("jobId", id, "structuredJd", extracted));
    }

    @GetMapping("/jobs")
    public List<Map<String, Object>> jobs() {
        log.debug("Entering JobController.jobs");
        return db.list("SELECT id,company_name,title,location,salary_disclosed,created_at FROM jobs WHERE user_id=? AND deleted_at IS NULL ORDER BY created_at DESC LIMIT 100", db.uid());
    }

    @GetMapping("/jobs/page")
    public CursorPage<Map<String, Object>> jobsPage(@RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "30") int size, @RequestParam(defaultValue = "") String q) {
        int limit = CursorPage.limit(size);
        var position = CursorPage.decode(cursor);
        String pattern = WorkspaceSearch.contains(WorkspaceSearch.term(q));
        String query = """
                SELECT id,company_name,title,location,salary_disclosed,created_at
                FROM jobs WHERE user_id=? AND deleted_at IS NULL
                AND (?='' OR concat_ws(' ', company_name, title) ILIKE ? ESCAPE '\\')
                """;
        String order = " ORDER BY created_at DESC,id DESC LIMIT ?";
        var rows = position == null ? db.list(query + order, db.uid(), pattern, pattern, limit + 1)
                : db.list(query + " AND (created_at,id)<(?,?)" + order, db.uid(), pattern, pattern,
                          position.timestamp(), position.id(), limit + 1);
        return CursorPage.rows(rows, limit, "createdAt");
    }

    @GetMapping("/jobs/{id}")
    public Map<String, Object> job(@PathVariable UUID id) {
        log.debug("Entering JobController.job");
        return db.one("SELECT * FROM jobs WHERE id=? AND user_id=? AND deleted_at IS NULL", id, db.uid());
    }

    @PutMapping("/jobs/{id}/structured-jd")
    public Map<String, Object> confirm(@PathVariable UUID id, @Valid @RequestBody WorkflowRequests.ConfirmJob body) {
        log.debug("Entering JobController.confirm");
        var existing = job(id);
        if (!body.confirm()) throw ApiError.invalid("Confirmed structured JD required");
        var parsed = body.structuredJd();
        AiContracts.job(parsed, (String) existing.get("rawJd"));
        db.jdbc.update("UPDATE jobs SET extracted_json=?::jsonb WHERE id=?", db.encode(parsed), id);
        return job(id);
    }

    @PostMapping("/analyses")
    public ResponseEntity<AiTaskService.AnalysisCreated> analyze(@Valid @RequestBody WorkflowRequests.Analyze body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ResponseEntity.accepted().body(tasks.analyze(body.jobId(), body.resumeVersionId(), key));
    }

    private String value(String field) { return field == null ? "" : field.strip(); }

    @GetMapping("/jobs/{id}/analyses")
    public List<Map<String, Object>> jobAnalyses(@PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page) {
        job(id);
        return db.list("""
                SELECT a.id,a.score,a.status,a.created_at,
                 (SELECT s.id FROM tailoring_sessions s WHERE s.analysis_id=a.id AND s.user_id=a.user_id
                  ORDER BY s.created_at DESC,s.id DESC LIMIT 1) AS tailoring_session_id
                FROM analyses a WHERE a.job_id=? AND a.user_id=?
                ORDER BY a.created_at DESC,a.id DESC LIMIT 50 OFFSET ?
                """, id, db.uid(), Math.max(0L, page) * 50L);
    }

    @GetMapping("/analyses")
    public List<Map<String, Object>> analyses() {
        log.debug("Entering JobController.analyses");
        return db.list("SELECT a.id,a.job_id,a.resume_version_id,a.status,a.score,a.created_at,j.title,j.company_name FROM analyses a JOIN jobs j ON a.job_id=j.id WHERE a.user_id=? ORDER BY a.created_at DESC LIMIT 100", db.uid());
    }

    @GetMapping("/analyses/{id}")
    public Map<String, Object> analysis(@PathVariable UUID id) {
        log.debug("Entering JobController.analysis");
        return db.one("""
                SELECT a.*,q.id AS ai_job_id,q.error_code,q.attempts
                FROM analyses a LEFT JOIN ai_jobs q ON q.resource_id=a.id AND q.task_type='ANALYSIS' AND q.user_id=a.user_id
                WHERE a.id=? AND a.user_id=?
                """, id, db.uid());
    }

    @GetMapping("/ai-jobs/{id}")
    public Map<String, Object> task(@PathVariable UUID id) {
        log.debug("Entering JobController.task");
        return db.one("SELECT id,task_type,resource_id,status,error_code,attempts,created_at,completed_at FROM ai_jobs WHERE id=? AND user_id=?", id, db.uid());
    }

    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/ai-jobs/{id}/retry")
    public Map<String, Object> retry(@PathVariable UUID id) {
        log.debug("Entering JobController.retry");
        var job = db.one("SELECT * FROM ai_jobs WHERE id=? AND user_id=? FOR UPDATE", id, db.uid());
        if (!"FAILED".equals(job.get("status")) || (Integer) job.get("attempts") >= 3)
            throw ApiError.conflict("This AI job cannot be retried");
        db.jdbc.update("UPDATE ai_jobs SET status='QUEUED',error_code=NULL,next_run_at=now(),completed_at=NULL,lease_until=NULL WHERE id=?", id);
        String task = (String) job.get("taskType");
        UUID resource = (UUID) job.get("resourceId");
        if (task.equals("ANALYSIS")) db.jdbc.update("UPDATE analyses SET status='QUEUED' WHERE id=?", resource);
        else db.jdbc.update("UPDATE tailoring_sessions SET status='QUEUED' WHERE id=?", resource);
        return task(id);
    }
}

