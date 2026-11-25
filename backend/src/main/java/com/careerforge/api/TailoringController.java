package com.careerforge.api;

import com.careerforge.core.ApiError;
import com.careerforge.core.Db;
import com.careerforge.ai.AiTaskService;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import com.careerforge.documents.DocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class TailoringController {
    private static final Logger log = LoggerFactory.getLogger(TailoringController.class);

    private final Db db;
    private final DocumentService docs;
    private final AiTaskService tasks;

    TailoringController(Db db, DocumentService docs, AiTaskService tasks) {
        this.db = db;
        this.docs = docs;
        this.tasks = tasks;
    }

    @PostMapping("/tailoring-sessions")
    public ResponseEntity<AiTaskService.TailoringCreated> start(@Valid @RequestBody WorkflowRequests.Tailor body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ResponseEntity.accepted().body(tasks.tailor(body.analysisId(), key));
    }

    @GetMapping("/tailoring-sessions/{id}")
    public Map<String, Object> session(@PathVariable UUID id) {
        log.debug("Entering TailoringController.session");
        Map<String, Object> out = new LinkedHashMap<>(db.one("""
                SELECT s.*,a.job_id,q.id AS ai_job_id,q.error_code,q.attempts
                FROM tailoring_sessions s JOIN analyses a ON a.id=s.analysis_id AND a.user_id=s.user_id
                LEFT JOIN ai_jobs q ON q.resource_id=s.id AND q.task_type='TAILORING' AND q.user_id=s.user_id
                WHERE s.id=? AND s.user_id=?
                """, id, db.uid()));
        out.put("proposals", db.list("SELECT * FROM edit_proposals WHERE session_id=? ORDER BY created_at,id", id));
        return out;
    }

    @Transactional
    @PatchMapping("/resume-edits/{id}")
    public Map<String, Object> decision(@PathVariable UUID id, @Valid @RequestBody WorkflowRequests.DecideProposal body) {
        log.debug("Entering TailoringController.decision");
        db.lockUser(db.uid());
        var edit = db.one("SELECT e.* FROM edit_proposals e JOIN tailoring_sessions s ON s.id=e.session_id WHERE e.id=? AND s.user_id=?", id, db.uid());
        String choice = body.decision();
        if (!Set.of("ACCEPTED", "REJECTED").contains(choice))
            throw ApiError.invalid("Decision must be ACCEPTED or REJECTED");
        int expected = body.revision() == null ? ((Number) edit.get("revision")).intValue() : body.revision();
        String proposed = body.proposedText() != null && "ACCEPTED".equals(choice)
                ? body.proposedText().strip() : (String) edit.get("proposedText");
        if (proposed.isBlank() || proposed.length() > 2000) throw ApiError.invalid("Edited text exceeds 2,000 characters");
        int count = db.jdbc.update("""
                UPDATE edit_proposals SET decision=?,proposed_text=?,revision=revision+1,
                risk=CASE WHEN proposed_text<>? THEN 'USER_EDITED' ELSE risk END WHERE id=? AND revision=?
                """, choice, proposed, proposed, id, expected);
        if (count != 1) throw ApiError.conflict("This edit changed. Reload and try again.");
        db.audit("RESUME_EDIT_" + choice, "edit", id);
        return db.one("SELECT * FROM edit_proposals WHERE id=?", id);
    }

    @Transactional
    @PutMapping("/resume-edits/{id}")
    public Map<String, Object> editText(@PathVariable UUID id, @Valid @RequestBody WorkflowRequests.EditProposal body) {
        log.debug("Entering TailoringController.editText");
        db.lockUser(db.uid());
        var edit = db.one("SELECT e.* FROM edit_proposals e JOIN tailoring_sessions s ON s.id=e.session_id WHERE e.id=? AND s.user_id=?", id, db.uid());
        String proposed = body.proposedText().strip();
        if (proposed.isBlank() || proposed.length() > 2000) throw ApiError.invalid("Edited text exceeds 2,000 characters");
        int revision = body.revision() == null ? ((Number) edit.get("revision")).intValue() : body.revision();
        int count = db.jdbc.update("UPDATE edit_proposals SET proposed_text=?,risk='USER_EDITED',decision='PENDING',revision=revision+1 WHERE id=? AND revision=?", proposed, id, revision);
        if (count == 0) throw ApiError.conflict("This proposal was changed elsewhere; reload and retry");
        db.audit("RESUME_EDIT_MANUALLY_MODIFIED", "edit", id);
        return db.one("SELECT * FROM edit_proposals WHERE id=?", id);
    }

    @GetMapping("/exports")
    public List<Map<String, Object>> list() {
        log.debug("Entering TailoringController.list");
        return db.list("""
                WITH files AS (
                    SELECT id,user_id,session_id,resume_version_id,format,filename,created_at,
                           'LEGACY' AS template,'/api/v1/exports/' AS download_path
                    FROM exports WHERE user_id=?
                    UNION ALL
                    SELECT id,user_id,tailoring_session_id,resume_version_id,format,filename,created_at,
                           'JAKE_RESUME_LATEX','/api/v1/jake-exports/'
                    FROM jake_resume_exports WHERE user_id=?
                )
                SELECT e.id,e.session_id,e.resume_version_id,e.format,e.filename,e.created_at,e.template,
                       e.download_path || e.id || '/download' AS download_url,
                       v.version_number,a.job_id,j.title AS job_title,j.company_name
                FROM files e JOIN resume_versions v ON v.id=e.resume_version_id
                LEFT JOIN tailoring_sessions s ON s.id=e.session_id AND s.user_id=e.user_id
                LEFT JOIN analyses a ON a.id=s.analysis_id AND a.user_id=s.user_id
                LEFT JOIN jobs j ON j.id=a.job_id AND j.user_id=a.user_id
                ORDER BY e.created_at DESC,e.id DESC
                """, db.uid(), db.uid());
    }

    @GetMapping("/exports/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        log.debug("Entering TailoringController.download");
        var file = db.owned("exports", id);
        byte[] bytes = docs.read(String.valueOf(file.get("storageKey")), db.uid());
        MediaType type = "PDF".equals(file.get("format")) ? MediaType.APPLICATION_PDF : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        return ResponseEntity.ok().contentType(type).header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.get("filename") + "\"").header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(bytes);
    }
}

