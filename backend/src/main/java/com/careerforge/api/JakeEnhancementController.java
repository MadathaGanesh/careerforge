package com.careerforge.api;

import com.careerforge.career.RecruiterOutreachService;
import com.careerforge.career.ResumeScoringService;
import com.careerforge.core.ApiError;
import com.careerforge.core.Db;
import com.careerforge.documents.DocumentService;
import com.careerforge.documents.JakeResumeService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import com.careerforge.core.Idempotency;
import com.careerforge.documents.ReviewedEdits;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class JakeEnhancementController {
    private static final Logger log = LoggerFactory.getLogger(JakeEnhancementController.class);
    private final Db db;
    private final DocumentService storage;
    private final JakeResumeService renderer;
    private final ResumeScoringService scoring;
    private final RecruiterOutreachService outreach;
    private final Idempotency requests;

    public JakeEnhancementController(Db db, DocumentService storage, JakeResumeService renderer,
                                     ResumeScoringService scoring, RecruiterOutreachService outreach,
                                     Idempotency requests) {
        this.db = db;
        this.storage = storage;
        this.renderer = renderer;
        this.scoring = scoring;
        this.outreach = outreach;
        this.requests = requests;
    }

    private static long elapsed(long startNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    @SuppressWarnings("unchecked")
    private Context context(UUID sessionId) {
        UUID uid = db.uid();
        log.debug("Loading tailoring context sessionId={}", sessionId);
        Map<String, Object> row = db.one("""
                SELECT t.id, t.status, t.source_version_id,
                       v.resume_id, v.content_text AS original_text, v.content_json,
                       j.title, j.company_name, j.raw_jd, COALESCE(a.jd_snapshot,j.extracted_json) AS extracted_json,
                       u.full_name, u.email
                FROM tailoring_sessions t
                JOIN analyses a ON a.id = t.analysis_id AND a.user_id = t.user_id
                JOIN jobs j ON j.id = a.job_id AND j.user_id = t.user_id
                JOIN resume_versions v ON v.id = t.source_version_id
                JOIN resumes r ON r.id = v.resume_id AND r.user_id = t.user_id
                JOIN app_users u ON u.id = t.user_id
                WHERE t.id = ? AND t.user_id = ?
                  AND r.deleted_at IS NULL AND u.deleted_at IS NULL
                """, sessionId, uid);
        String original = String.valueOf(row.get("originalText"));
        var reviewed = ReviewedEdits.apply(original, db.list("""
                SELECT id, original_text, proposed_text, decision, reason, evidence, risk, revision, source_start, source_end, source_sha256
                FROM edit_proposals WHERE session_id=? ORDER BY created_at,id
                """, sessionId));
        Object parsed = row.get("extractedJson");
        Map<String, Object> jd = parsed instanceof Map<?, ?> ? (Map<String, Object>) parsed : Map.of();
        return new Context(row, original, reviewed.text(), jd, reviewed.approved(), reviewed.pending());

    }

    @GetMapping("/tailoring-sessions/{id}/enhancement-report")
    public Map<String, Object> report(@PathVariable UUID id) {
        long started = System.nanoTime();
        log.info("Enhancement report requested sessionId={}", id);
        Context c = context(id);
        if (!"SUCCEEDED".equals(c.row().get("status"))) {
            log.warn("Enhancement report blocked sessionId={} status={}", id, c.row().get("status"));
            throw ApiError.conflict("Finish generating tailoring proposals before scoring");
        }
        Map<String, Object> result = new LinkedHashMap<>(scoring.compare(c.original(), c.revised(), c.jd(), c.approved()));
        result.put("pendingEdits", c.pending());
        result.put("template", "JAKE_RESUME_LATEX");
        log.info("Enhancement report generated sessionId={} approvedEdits={} pendingEdits={} durationMs={}",
                id, c.approved().size(), c.pending(), elapsed(started));
        return result;
    }

    @GetMapping("/tailoring-sessions/{id}/recruiter-draft")
    public Map<String, Object> recruiterDraft(@PathVariable UUID id,
                                              @RequestParam(required = false) String recipient) {
        long started = System.nanoTime();
        log.info("Recruiter draft requested sessionId={} manualRecipientProvided={}",
                id, recipient != null && !recipient.isBlank());
        Context c = context(id);
        if (!"SUCCEEDED".equals(c.row().get("status"))) {
            log.warn("Recruiter draft blocked sessionId={} status={}", id, c.row().get("status"));
            throw ApiError.conflict("Tailoring must complete before preparing recruiter outreach");
        }
        Map<String, Object> draft = outreach.draft(String.valueOf(c.row().get("rawJd")),
                String.valueOf(c.row().get("title")),
                String.valueOf(c.row().get("companyName")),
                String.valueOf(c.row().get("fullName")),
                String.valueOf(c.row().get("email")), c.revised(), c.jd(), recipient);
        log.info("Recruiter draft generated sessionId={} suggestedRecipientCount={} durationMs={}",
                id, ((List<?>) draft.get("suggestedRecipients")).size(), elapsed(started));
        return draft;
    }

    @GetMapping("/export-capabilities")
    public JakeResumeService.PdfCapability capabilities() {
        return renderer.capability();
    }

    @Transactional
    @PostMapping({"/tailoring-sessions/{id}/exports", "/tailoring-sessions/{id}/jake-exports"})
    public ResponseEntity<Map<String, Object>> createJakeExport(@PathVariable UUID id,
            @Valid @RequestBody WorkflowRequests.Export body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        String format = body.format().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("PDF", "TEX").contains(format)) throw ApiError.invalid("New tailored resumes use Jake’s Resume. Choose PDF or TEX.");
        return ResponseEntity.status(HttpStatus.CREATED).body(requests.run("JAKE_EXPORT", key, id + ":" + format,
                () -> createExport(id, format)));
    }

    private Map<String, Object> createExport(UUID id, String format) {
        UUID owner = db.uid();
        db.lockUser(owner);
        Context c = context(id);
        if (!"SUCCEEDED".equals(c.row().get("status"))) throw ApiError.conflict("Tailoring must be complete before export");
        if (c.pending() > 0) throw ApiError.conflict("Accept or reject all proposed edits before exporting");
        String name = "";
        if (c.row().get("contentJson") instanceof Map<?, ?> content && content.get("candidate") instanceof Map<?, ?> candidate
                && candidate.get("name") instanceof String reviewedName && !reviewedName.isBlank()) name = reviewedName;
        byte[] latex = renderer.latex(c.revised(), name.length() > 120 ? "" : name, "");
        byte[] document = "PDF".equals(format) ? renderer.pdf(latex) : latex;
        String extension = format.toLowerCase(Locale.ROOT);
        String filename = "CareerForge_Jakes_Tailored_Resume." + extension;
        var path = storage.storeForTransaction(owner, document, extension);
        UUID resumeId = (UUID) c.row().get("resumeId");
        int previous = db.jdbc.queryForObject("SELECT max(version_number) FROM resume_versions WHERE resume_id=?", Integer.class, resumeId);
        UUID version = UUID.randomUUID(), exportId = UUID.randomUUID();
        db.jdbc.update("""
                INSERT INTO resume_versions(id,resume_id,version_number,content_text,content_json,parent_version_id,source_job_id)
                VALUES (?,?,?,?,?::jsonb,?,(SELECT a.job_id FROM tailoring_sessions t JOIN analyses a ON a.id=t.analysis_id WHERE t.id=?))
                """, version, resumeId, previous + 1, c.revised(), db.encode(c.row().get("contentJson")),
                c.row().get("sourceVersionId"), id);
        db.jdbc.update("""
                INSERT INTO jake_resume_exports(id,user_id,tailoring_session_id,resume_version_id,format,storage_key,filename,sha256)
                VALUES (?,?,?,?,?,?,?,?)
                """, exportId, owner, id, version, format, path.toString(), filename, storage.sha(document));
        db.audit("JAKE_RESUME_EXPORTED", "resume", resumeId);
        return Map.of("exportId", exportId, "versionId", version, "format", format,
                "downloadUrl", "/api/v1/jake-exports/" + exportId + "/download", "filename", filename);
    }

    @GetMapping("/jake-exports")
    public List<Map<String, Object>> list(@RequestParam(required = false) UUID sessionId) {
        log.debug("Listing Jake exports filteredBySession={}", sessionId != null);
        if (sessionId == null) return db.list("""
                SELECT id,tailoring_session_id,resume_version_id,format,filename,created_at
                FROM jake_resume_exports WHERE user_id=? ORDER BY created_at DESC LIMIT 100
                """, db.uid());
        return db.list("""
                SELECT id,tailoring_session_id,resume_version_id,format,filename,created_at
                FROM jake_resume_exports WHERE user_id=? AND tailoring_session_id=? ORDER BY created_at DESC LIMIT 100
                """, db.uid(), sessionId);
    }

    @GetMapping("/jake-exports/{id}/download")
    public ResponseEntity<byte[]> downloadJake(@PathVariable UUID id) {
        long started = System.nanoTime();
        log.info("Jake export download requested exportId={}", id);
        Map<String, Object> file = db.one("SELECT * FROM jake_resume_exports WHERE id=? AND user_id=?", id, db.uid());
        String format = String.valueOf(file.get("format"));
        MediaType mime = "PDF".equals(format) ? MediaType.APPLICATION_PDF
                : MediaType.parseMediaType("application/x-tex; charset=UTF-8");
        byte[] contents = storage.read(String.valueOf(file.get("storageKey")), db.uid());
        log.info("Jake export download prepared exportId={} format={} bytes={} durationMs={}",
                id, format, contents.length, elapsed(started));
        return ResponseEntity.ok().contentType(mime)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.get("filename") + "\"")
                .header("Cache-Control", "private, no-store")
                .body(contents);
    }

    private record Context(Map<String, Object> row, String original, String revised,
                           Map<String, Object> jd, List<Map<String, Object>> approved, int pending) {
    }
}

