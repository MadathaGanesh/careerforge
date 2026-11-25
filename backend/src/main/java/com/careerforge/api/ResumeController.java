package com.careerforge.api;

import com.careerforge.documents.ResumeCreation;
import com.careerforge.core.ApiError;
import com.careerforge.core.Db;
import com.careerforge.core.CursorPage;
import jakarta.validation.Valid;
import com.careerforge.documents.DocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ResumeController {
    private static final Logger log = LoggerFactory.getLogger(ResumeController.class);

    private final Db db;
    private final DocumentService docs;
    private final ResumeCreation creation;

    private final com.careerforge.core.PrivacyService privacy;

    ResumeController(Db db, DocumentService docs, ResumeCreation creation, com.careerforge.core.PrivacyService privacy) {
        this.db = db;
        this.docs = docs;
        this.creation = creation;
        this.privacy = privacy;
    }

    @PostMapping(value = "/resumes/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ResumeCreation.Created> upload(@RequestParam("file") MultipartFile file) {
        var parsed = docs.extract(file);
        return ResponseEntity.status(201).body(creation.create(parsed.name(), parsed.text(), parsed.mime(), parsed.original()));
    }

    @PostMapping("/resumes/text")
    public ResponseEntity<ResumeCreation.Created> paste(@Valid @RequestBody WorkflowRequests.PasteResume body) {
        return ResponseEntity.status(201).body(creation.create(
                body.title().strip(), body.pastedText().strip(), "text/plain", null));
    }

    @GetMapping("/resumes")
    public List<Map<String, Object>> all() {
        log.debug("Entering ResumeController.all");
        return db.list("SELECT r.id,r.title,r.content_type,r.created_at, (SELECT COUNT(*) FROM resume_versions v WHERE v.resume_id=r.id) AS versions, EXISTS (SELECT 1 FROM resume_versions v WHERE v.resume_id=r.id AND v.confirmed_at IS NOT NULL) AS ready FROM resumes r WHERE r.user_id=? AND r.deleted_at IS NULL ORDER BY r.created_at DESC LIMIT 100", db.uid());
    }

    @GetMapping("/resumes/page")
    public CursorPage<Map<String, Object>> page(@RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "30") int size, @RequestParam(defaultValue = "") String q) {
        int limit = CursorPage.limit(size);
        var position = CursorPage.decode(cursor);
        String pattern = WorkspaceSearch.contains(WorkspaceSearch.term(q));
        String query = """
                SELECT r.id,r.title,r.content_type,r.created_at,
                 (SELECT count(*) FROM resume_versions v WHERE v.resume_id=r.id) AS versions,
                 EXISTS (SELECT 1 FROM resume_versions v WHERE v.resume_id=r.id AND v.confirmed_at IS NOT NULL) AS ready
                FROM resumes r WHERE r.user_id=? AND r.deleted_at IS NULL
                AND (?='' OR r.title ILIKE ? ESCAPE '\\')
                """;
        String order = " ORDER BY r.created_at DESC,r.id DESC LIMIT ?";
        var rows = position == null ? db.list(query + order, db.uid(), pattern, pattern, limit + 1)
                : db.list(query + " AND (r.created_at,r.id)<(?,?)" + order, db.uid(), pattern, pattern,
                          position.timestamp(), position.id(), limit + 1);
        return CursorPage.rows(rows, limit, "createdAt");
    }

    @GetMapping("/resumes/{id}")
    public Map<String, Object> resume(@PathVariable UUID id) {
        log.debug("Entering ResumeController.resume");
        return db.one("SELECT * FROM resumes WHERE id=? AND user_id=? AND deleted_at IS NULL", id, db.uid());
    }

    @GetMapping("/resumes/{id}/versions")
    public List<Map<String, Object>> versions(@PathVariable UUID id) {
        log.debug("Entering ResumeController.versions");
        resume(id);
        return db.list("SELECT id,resume_id,version_number,content_text,content_json,parent_version_id,source_job_id,created_at,confirmed_at FROM resume_versions WHERE resume_id=? ORDER BY version_number DESC", id);
    }

    @GetMapping("/resume-versions/{id}")
    public Map<String, Object> version(@PathVariable UUID id) {
        log.debug("Entering ResumeController.version");
        return db.one("SELECT v.* FROM resume_versions v JOIN resumes r ON r.id=v.resume_id WHERE v.id=? AND r.user_id=? AND r.deleted_at IS NULL", id, db.uid());
    }

    @org.springframework.transaction.annotation.Transactional
    @PutMapping("/resume-versions/{id}/structured-content")
    public Map<String, Object> confirm(@PathVariable UUID id, @Valid @RequestBody WorkflowRequests.ConfirmResume body) {
        db.lockUser(db.uid());
        Map<String, Object> existing = version(id);
        if (!body.confirm()) throw ApiError.invalid("Explicit confirmation required");
        var content = body.structuredContent();
        com.careerforge.ai.AiContracts.resume(content);
        String text = body.contentText() == null ? (String) existing.get("contentText") : body.contentText().strip();
        // Confirmed skill fields are attested by the candidate. Include them in the same text all consumers use.
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        var missing = com.careerforge.ai.AiContracts.skills(content).stream()
                .filter(skill -> !java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])"
                        + java.util.regex.Pattern.quote(skill.toLowerCase(java.util.Locale.ROOT))
                        + "(?![\\p{L}\\p{N}])").matcher(lower).find())
                .distinct().toList();
        if (!missing.isEmpty()) text += "\n\nSkills: " + String.join(", ", missing);
        if (text.length() < 25 || text.length() > 100000 || text.indexOf(0) >= 0)
            throw ApiError.invalid("Reviewed resume text must be between 25 and 100,000 characters");
        if (text.equals(existing.get("contentText")) && content.equals(existing.get("contentJson"))
                && existing.get("confirmedAt") != null) {
            return Map.of("versionId", id, "reviewStatus", "CONFIRMED");
        }
        UUID resume = (UUID) existing.get("resumeId");
        UUID confirmed = UUID.randomUUID();
        int next = db.jdbc.queryForObject("SELECT max(version_number)+1 FROM resume_versions WHERE resume_id=?", Integer.class, resume);
        db.jdbc.update("""
                INSERT INTO resume_versions(id,resume_id,version_number,content_text,content_json,parent_version_id,confirmed_at)
                VALUES (?,?,?,?,?::jsonb,?,now())
                """, confirmed, resume, next, text, db.encode(content), id);
        db.audit("RESUME_CONFIRMED", "resume", resume);
        return Map.of("versionId", confirmed, "reviewStatus", "CONFIRMED");
    }

    @org.springframework.transaction.annotation.Transactional
    @DeleteMapping("/resumes/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        log.debug("Entering ResumeController.delete");
        db.lockUser(db.uid());
        resume(id);
        db.jdbc.update("UPDATE resumes SET deleted_at=now() WHERE id=?", id);
        db.audit("RESUME_SOFT_DELETED", "resume", id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/resumes/archived")
    public List<Map<String, Object>> archived() {
        return db.list("SELECT id,title,deleted_at FROM resumes WHERE user_id=? AND deleted_at IS NOT NULL ORDER BY deleted_at DESC", db.uid());
    }

    @PostMapping("/resumes/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable UUID id) {
        if (db.jdbc.update("UPDATE resumes SET deleted_at=NULL WHERE id=? AND user_id=? AND deleted_at IS NOT NULL", id, db.uid()) != 1)
            throw ApiError.notFound();
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/resumes/{id}/permanent")
    public ResponseEntity<Void> permanentlyDelete(@PathVariable UUID id, @RequestBody WorkspaceRequests.Confirmation body) {
        if (!"DELETE RESUME".equals(body.confirmation())) throw ApiError.invalid("Confirmation must equal DELETE RESUME");
        privacy.deleteResume(db.uid(), id);
        return ResponseEntity.noContent().build();
    }
}

