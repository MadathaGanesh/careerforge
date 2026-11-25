package com.careerforge.api;

import com.careerforge.ai.CareerAi;
import com.careerforge.core.ApiError;
import com.careerforge.core.Db;
import com.careerforge.core.CursorPage;
import com.careerforge.core.Quota;
import com.careerforge.core.PrivacyService;
import com.careerforge.documents.DocumentService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class WorkspaceController {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceController.class);

    private final Db db;
    private final CareerAi ai;
    private final DocumentService docs;
    private final Quota quota;

    private final PrivacyService privacy;

    WorkspaceController(Db db, CareerAi ai, DocumentService docs, Quota quota, PrivacyService privacy) {
        this.db = db;
        this.ai = ai;
        this.docs = docs;
        this.quota = quota;
        this.privacy = privacy;
    }

    @GetMapping("/users/me")
    public Map<String, Object> me() {
        log.debug("Entering WorkspaceController.me");
        UUID id = db.uid();
        Map<String, Object> out = new LinkedHashMap<>(db.one("SELECT id,email,full_name,email_verified,created_at,password_hash IS NOT NULL AS has_password, EXISTS (SELECT 1 FROM auth_identities WHERE user_id=app_users.id AND provider='GOOGLE') AS has_google FROM app_users WHERE id=? AND deleted_at IS NULL", id));
        out.put("profile", db.one("SELECT * FROM user_profiles WHERE user_id=?", id));
        return out;
    }

    @PutMapping("/users/me/account")
    public Map<String, Object> account(@Valid @RequestBody WorkspaceRequests.Account body) {
        log.debug("Entering WorkspaceController.account");
        String name = body.name().trim();
        db.jdbc.update("UPDATE app_users SET full_name=? WHERE id=?", name, db.uid());
        return db.one("SELECT id,email,full_name,email_verified FROM app_users WHERE id=?", db.uid());
    }

    @PutMapping("/users/me/profile")
    public Map<String, Object> profile(@Valid @RequestBody WorkspaceRequests.Profile body) {
        log.debug("Entering WorkspaceController.profile");
        BigDecimal experience = body.yearsExperience() == null ? BigDecimal.ZERO : body.yearsExperience();
        var skills = body.skills() == null ? List.of() : body.skills();
        var roles = body.targetRoles() == null ? List.of() : body.targetRoles();
        db.jdbc.update("""
                UPDATE user_profiles SET headline=?,years_experience=?,location=?,skills=?::jsonb,target_roles=?::jsonb,
                preferences=COALESCE(?::jsonb,preferences),updated_at=now() WHERE user_id=?
                """, text(body.headline()), experience, text(body.location()), db.encode(skills), db.encode(roles),
                body.preferences() == null ? null : db.encode(body.preferences()), db.uid());
        return db.one("SELECT * FROM user_profiles WHERE user_id=?", db.uid());
    }

    @GetMapping("/dashboard/summary")
    public Map<String, Object> summary() {
        log.debug("Entering WorkspaceController.summary");
        UUID u = db.uid();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("resumes", count("SELECT count(*) FROM resumes WHERE user_id=? AND deleted_at IS NULL", u));
        out.put("analyses", count("SELECT count(*) FROM analyses WHERE user_id=? AND status='SUCCEEDED'", u));
        out.put("jobs", count("SELECT count(*) FROM jobs WHERE user_id=?", u));
        out.put("applications", count("SELECT count(*) FROM applications WHERE user_id=?", u));
        out.put("interviewSessions", count("SELECT count(*) FROM interview_sessions WHERE user_id=?", u));
        out.put("recentAnalyses", db.list("SELECT a.id,a.score,a.status,j.title,j.company_name,a.created_at FROM analyses a JOIN jobs j ON a.job_id=j.id WHERE a.user_id=? ORDER BY a.created_at DESC LIMIT 8", u));
        out.put("reminders", db.list("SELECT id,title,due_at,completed FROM reminders WHERE user_id=? AND completed=false ORDER BY due_at LIMIT 8", u));
        out.put("recentApplications", db.list("""
                SELECT a.id,a.status,j.title,j.company_name FROM applications a JOIN jobs j ON j.id=a.job_id
                WHERE a.user_id=? ORDER BY a.updated_at DESC LIMIT 4
                """, u));
        out.put("recentTailoring", db.list("""
                SELECT s.id,j.title,j.company_name FROM tailoring_sessions s
                JOIN analyses a ON a.id=s.analysis_id AND a.user_id=s.user_id
                JOIN jobs j ON j.id=a.job_id AND j.user_id=s.user_id
                WHERE s.user_id=? AND s.status IN ('QUEUED','RUNNING','SUCCEEDED')
                AND NOT EXISTS (SELECT 1 FROM exports e WHERE e.session_id=s.id)
                ORDER BY s.created_at DESC LIMIT 1
                """, u).stream().findFirst().orElse(null));
        out.put("mode", ai.mode());
        return out;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String currency(String value) {
        String normalized = text(value);
        return normalized.isEmpty() ? "INR" : normalized.toUpperCase(Locale.ROOT);
    }

    private Integer count(String sql, Object... args) {
        log.debug("Entering WorkspaceController.count");
        return db.jdbc.queryForObject(sql, Integer.class, args);
    }

    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/interview-questions/generate")
    public Map<String, Object> questions(@Valid @RequestBody WorkflowRequests.GenerateInterview body) {
        log.debug("Entering WorkspaceController.questions");
        UUID jobId = body.jobId();
        Map<String, Object> job = db.owned("jobs", jobId);
        String resume = "";
        if (body.resumeVersionId() != null) {
            UUID vid = body.resumeVersionId();
            resume = (String) db.one("SELECT v.content_text FROM resume_versions v JOIN resumes r ON r.id=v.resume_id WHERE v.id=? AND r.user_id=? AND r.deleted_at IS NULL AND v.confirmed_at IS NOT NULL", vid, db.uid()).get("contentText");
        }
        quota.consume("INTERVIEW_QUESTIONS");
        List<Map<String, Object>> generated = ai.questions(String.valueOf(job.get("rawJd")), resume + "\nConfirmed career context:" + ai.careerMemory(db.uid()));
        List<Map<String, Object>> saved = new ArrayList<>();
        for (var q : generated) {
            UUID id = UUID.randomUUID();
            db.jdbc.update("INSERT INTO interview_questions(id,user_id,job_id,category,question,suggested_answer) VALUES (?,?,?,?,?,?)", id, db.uid(), jobId, q.get("category"), q.get("question"), q.get("suggestedAnswer"));
            saved.add(db.one("SELECT * FROM interview_questions WHERE id=?", id));
        }
        return Map.of("questions", saved, "mode", ai.mode());
    }

    @GetMapping("/interview-questions")
    public List<Map<String, Object>> questionList(@RequestParam UUID jobId) {
        log.debug("Entering WorkspaceController.questionList");
        db.owned("jobs", jobId);
        return db.list("SELECT * FROM interview_questions WHERE user_id=? AND job_id=? ORDER BY created_at,id", db.uid(), jobId);
    }

    @PostMapping("/interview-sessions")
    public Map<String, Object> interviewSession(@Valid @RequestBody WorkflowRequests.StartInterview body) {
        log.debug("Entering WorkspaceController.interviewSession");
        UUID job = body.jobId();
        db.owned("jobs", job);
        UUID id = UUID.randomUUID();
        db.jdbc.update("INSERT INTO interview_sessions(id,user_id,job_id) VALUES (?,?,?)", id, db.uid(), job);
        return db.owned("interview_sessions", id);
    }

    @GetMapping("/interview-sessions")
    public List<Map<String, Object>> interviewSessions() {
        log.debug("Entering WorkspaceController.interviewSessions");
        return db.list("SELECT s.*,j.title,j.company_name FROM interview_sessions s LEFT JOIN jobs j ON j.id=s.job_id WHERE s.user_id=? ORDER BY s.created_at DESC", db.uid());
    }

    @GetMapping("/interview-sessions/{id}")
    public Map<String, Object> interviewDetails(@PathVariable UUID id) {
        log.debug("Entering WorkspaceController.interviewDetails");
        Map<String, Object> result = new LinkedHashMap<>(db.owned("interview_sessions", id));
        result.put("answers", db.list("SELECT a.*,q.question FROM interview_answers a JOIN interview_questions q ON q.id=a.question_id WHERE a.session_id=? ORDER BY a.created_at", id));
        return result;
    }

    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/interview-sessions/{id}/answers")
    public Map<String, Object> answer(@PathVariable UUID id, @Valid @RequestBody WorkflowRequests.RecordAnswer body) {
        log.debug("Entering WorkspaceController.answer");
        Map<String, Object> s = db.one("SELECT * FROM interview_sessions WHERE id=? AND user_id=? FOR UPDATE", id, db.uid());
        if (!"IN_PROGRESS".equals(s.get("status"))) throw ApiError.conflict("Interview session already closed");
        UUID question = body.questionId();
        var q = db.one("SELECT * FROM interview_questions WHERE id=? AND user_id=? AND job_id=?", question, db.uid(), s.get("jobId"));
        String answer = body.answer().trim();
        Map<String, Object> f = ai.feedback((String) q.get("question"), answer);
        UUID aid = UUID.randomUUID();
        Number score = (Number) f.get("score");
        db.jdbc.update("INSERT INTO interview_answers(id,session_id,question_id,answer,feedback_json,score) VALUES (?,?,?,?,?::jsonb,?) ON CONFLICT(session_id,question_id) DO UPDATE SET answer=excluded.answer,feedback_json=excluded.feedback_json,score=excluded.score", aid, id, question, answer, db.encode(f), score);
        return Map.of("feedback", f, "sessionId", id);
    }

    @PostMapping("/interview-sessions/{id}/complete")
    public Map<String, Object> complete(@PathVariable UUID id) {
        log.debug("Entering WorkspaceController.complete");
        db.owned("interview_sessions", id);
        db.jdbc.update("UPDATE interview_sessions SET status='COMPLETED',overall_score=(SELECT AVG(score) FROM interview_answers WHERE session_id=?),completed_at=now() WHERE id=? AND user_id=? AND status='IN_PROGRESS'", id, id, db.uid());
        return db.owned("interview_sessions", id);
    }

    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/applications")
    public Map<String, Object> application(@Valid @RequestBody WorkspaceRequests.Application body) {
        log.debug("Entering WorkspaceController.application");
        UUID job = body.jobId();
        db.owned("jobs", job);
        UUID version = body.resumeVersionId();
        if (version != null)
            db.one("SELECT v.id FROM resume_versions v JOIN resumes r ON r.id=v.resume_id WHERE v.id=? AND r.user_id=?", version, db.uid());
        UUID id = UUID.randomUUID();
        db.jdbc.update("INSERT INTO applications(id,user_id,job_id,resume_version_id,notes) VALUES (?,?,?,?,?)", id, db.uid(), job, version, text(body.notes()));
        db.jdbc.update("INSERT INTO application_events(id,application_id,new_status) VALUES (?,?,'SAVED')", UUID.randomUUID(), id);
        return db.owned("applications", id);
    }

    @GetMapping("/applications")
    public List<Map<String, Object>> applications() {
        log.debug("Entering WorkspaceController.applications");
        return db.list("SELECT a.*,j.title,j.company_name,j.location FROM applications a JOIN jobs j ON a.job_id=j.id WHERE a.user_id=? ORDER BY a.updated_at DESC LIMIT 300", db.uid());
    }

    @GetMapping("/applications/page")
    public CursorPage<Map<String, Object>> applicationsPage(@RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int size, @RequestParam(defaultValue = "") String q) {
        int limit = CursorPage.limit(size);
        var position = CursorPage.decode(cursor);
        String pattern = WorkspaceSearch.contains(WorkspaceSearch.term(q));
        String query = """
                SELECT a.*,j.title,j.company_name,j.location FROM applications a JOIN jobs j ON a.job_id=j.id
                WHERE a.user_id=? AND (?='' OR concat_ws(' ', j.company_name, j.title) ILIKE ? ESCAPE '\\')
                """;
        String order = " ORDER BY a.updated_at DESC,a.id DESC LIMIT ?";
        var rows = position == null ? db.list(query + order, db.uid(), pattern, pattern, limit + 1)
                : db.list(query + " AND (a.updated_at,a.id)<(?,?)" + order, db.uid(), pattern, pattern,
                          position.timestamp(), position.id(), limit + 1);
        return CursorPage.rows(rows, limit, "updatedAt");
    }

    @GetMapping("/applications/{id}")
    public Map<String, Object> applicationDetails(@PathVariable UUID id) {
        log.debug("Entering WorkspaceController.applicationDetails");
        Map<String, Object> result = new LinkedHashMap<>(db.one("""
                SELECT a.*,j.title,j.company_name,j.location,j.salary_disclosed,j.job_url
                FROM applications a JOIN jobs j ON j.id=a.job_id AND j.user_id=a.user_id
                WHERE a.id=? AND a.user_id=?
                """, id, db.uid()));
        result.put("events", db.list("SELECT * FROM application_events WHERE application_id=? ORDER BY created_at", id));
        return result;
    }

    @org.springframework.transaction.annotation.Transactional
    @PatchMapping("/applications/{id}")
    public Map<String, Object> editApplication(@PathVariable UUID id, @Valid @RequestBody WorkspaceRequests.EditApplication body) {
        log.debug("Entering WorkspaceController.editApplication");
        var previous = db.one("SELECT * FROM applications WHERE id=? AND user_id=? FOR UPDATE", id, db.uid());
        String status = text(body.status());
        if (status.isBlank()) status = (String) previous.get("status");
        if (!Set.of("SAVED", "READY_TO_APPLY", "APPLIED", "SCREENING", "INTERVIEW", "OFFER", "ACCEPTED", "REJECTED", "WITHDRAWN", "CLOSED").contains(status))
            throw ApiError.invalid("Unknown application status");
        db.jdbc.update("UPDATE applications SET status=?,notes=?,salary_offered=?,applied_at=CASE WHEN ?='APPLIED' AND applied_at IS NULL THEN now() ELSE applied_at END,updated_at=now() WHERE id=?", status, body.notes() == null ? previous.get("notes") : body.notes(),
                body.salaryOffered() == null ? previous.get("salaryOffered") : body.salaryOffered(), status, id);
        if (!previous.get("status").equals(status))
            db.jdbc.update("INSERT INTO application_events(id,application_id,old_status,new_status) VALUES (?,?,?,?)", UUID.randomUUID(), id, previous.get("status"), status);
        return db.owned("applications", id);
    }

    @DeleteMapping("/applications/{id}")
    public ResponseEntity<Void> deleteApplication(@PathVariable UUID id) {
        log.debug("Entering WorkspaceController.deleteApplication");
        db.owned("applications", id);
        db.jdbc.update("DELETE FROM applications WHERE id=?", id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reminders")
    public Map<String, Object> reminder(@Valid @RequestBody WorkspaceRequests.Reminder body) {
        log.debug("Entering WorkspaceController.reminder");
        String title = body.title().trim();
        Instant when;
        try {
            when = Instant.parse(body.dueAt().trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiError.invalid("dueAt must be ISO-8601 UTC date-time");
        }
        UUID app = body.applicationId();
        if (app != null) db.owned("applications", app);
        UUID id = UUID.randomUUID();
        db.jdbc.update("INSERT INTO reminders(id,user_id,application_id,title,due_at) VALUES (?,?,?,?,?)", id, db.uid(), app, title, java.sql.Timestamp.from(when));
        return db.one("SELECT * FROM reminders WHERE id=?", id);
    }

    @GetMapping("/reminders")
    public List<Map<String, Object>> reminders() {
        log.debug("Entering WorkspaceController.reminders");
        return db.list("SELECT * FROM reminders WHERE user_id=? ORDER BY due_at", db.uid());
    }

    @PatchMapping("/reminders/{id}")
    public Map<String, Object> reminderStatus(@PathVariable UUID id, @Valid @RequestBody WorkspaceRequests.ReminderStatus body) {
        log.debug("Entering WorkspaceController.reminderStatus");
        int n = db.jdbc.update("UPDATE reminders SET completed=? WHERE id=? AND user_id=?", body.completed(), id, db.uid());
        if (n == 0) throw ApiError.notFound();
        return db.one("SELECT * FROM reminders WHERE id=?", id);
    }

    @PostMapping("/salary-observations")
    public Map<String, Object> salaryEvidence(@Valid @RequestBody WorkspaceRequests.SalaryObservation body) {
        log.debug("Entering WorkspaceController.salaryEvidence");
        if (body.highValue().compareTo(body.lowValue()) < 0) throw ApiError.invalid("Invalid salary range");
        String sourceUrl = text(body.sourceUrl());
        if (!sourceUrl.isEmpty()) {
            URI uri = URI.create(sourceUrl);
            if (uri.getHost() == null || uri.getUserInfo() != null
                    || !Set.of("http", "https").contains(text(uri.getScheme()).toLowerCase(Locale.ROOT))) {
                throw ApiError.invalid("Salary source URL must be an HTTP or HTTPS link");
            }
        }
        UUID id = UUID.randomUUID();
        db.jdbc.update("""
                INSERT INTO salary_observations(id,user_id,role,location,experience_years,low_value,high_value,currency,
                source_label,source_url,observed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, id, db.uid(), body.role().trim(), body.location().trim(), body.experienceYears(), body.lowValue(),
                body.highValue(), currency(body.currency()), body.sourceLabel().trim(), sourceUrl, body.observedAt());
        return db.one("SELECT * FROM salary_observations WHERE id=?", id);
    }

    @GetMapping("/salary-observations")
    public List<Map<String, Object>> salaryObservations() {
        log.debug("Entering WorkspaceController.salaryObservations");
        return db.list("SELECT * FROM salary_observations WHERE user_id=? ORDER BY observed_at DESC", db.uid());
    }

    @PostMapping("/salary-estimates")
    public Map<String, Object> salaryEstimate(@Valid @RequestBody WorkspaceRequests.SalaryEstimate body) {
        log.debug("Entering WorkspaceController.salaryEstimate");
        String role = body.role().trim(), loc = body.location().trim(), currency = currency(body.currency());
        List<Map<String, Object>> data = db.list("SELECT low_value,high_value,source_label,source_url,observed_at,compensation_type FROM salary_observations WHERE user_id=? AND lower(role)=lower(?) AND lower(location)=lower(?) AND upper(currency)=? AND observed_at>current_date-interval '18 months' ORDER BY observed_at DESC", db.uid(), role, loc, currency);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", role);
        result.put("location", loc);
        result.put("currency", currency);
        result.put("observations", data);
        result.put("dataScope", "USER_ENTERED_SOURCES_NOT_A_REPRESENTATIVE_MARKET_SAMPLE");
        if (data.size() < 3) {
            result.put("status", "INSUFFICIENT_DATA");
            result.put("message", "Provide at least 3 recent, permitted and verifiable salary observations for this role and location.");
        } else {
            BigDecimal lo = BigDecimal.ZERO, hi = BigDecimal.ZERO;
            for (var item : data) {
                lo = lo.add((BigDecimal) item.get("lowValue"));
                hi = hi.add((BigDecimal) item.get("highValue"));
            }
            result.put("status", "AVAILABLE");
            result.put("lowEstimate", lo.divide(BigDecimal.valueOf(data.size()), 2, java.math.RoundingMode.HALF_UP));
            result.put("highEstimate", hi.divide(BigDecimal.valueOf(data.size()), 2, java.math.RoundingMode.HALF_UP));
            result.put("disclaimer", "Descriptive average of supplied evidence, not a validated market salary estimate.");
        }
        UUID id = UUID.randomUUID();
        db.jdbc.update("INSERT INTO salary_estimates(id,user_id,result_json) VALUES (?,?,?::jsonb)", id, db.uid(), db.encode(result));
        result.put("id", id);
        return result;
    }

    @GetMapping("/salary-estimates")
    public List<Map<String, Object>> estimates() {
        log.debug("Entering WorkspaceController.estimates");
        return db.list("SELECT * FROM salary_estimates WHERE user_id=? ORDER BY created_at DESC LIMIT 50", db.uid());
    }

    @GetMapping("/insights")
    public Map<String, Object> insights() {
        log.debug("Entering WorkspaceController.insights");
        UUID uid = db.uid();
        return Map.of(
                "applicationStages", db.list("SELECT status,count(*) AS count FROM applications WHERE user_id=? GROUP BY status", uid),
                "analysisScores", db.list("SELECT id,score,created_at FROM analyses WHERE user_id=? AND status='SUCCEEDED' ORDER BY created_at DESC LIMIT 30", uid),
                "interviews", db.list("SELECT id,overall_score,created_at FROM interview_sessions WHERE user_id=? AND status='COMPLETED' ORDER BY created_at DESC LIMIT 30", uid),
                "disclaimer", "Only your saved activity is measured. Trends do not establish causation.");
    }

    @GetMapping("/offers/compare")
    public Map<String, Object> offers() {
        log.debug("Entering WorkspaceController.offers");
        return Map.of("offers", db.list("SELECT a.id,a.salary_offered,a.status,j.title,j.company_name,j.location FROM applications a JOIN jobs j ON j.id=a.job_id WHERE a.user_id=? AND a.status IN ('OFFER','ACCEPTED')", db.uid()), "disclaimer", "Review full compensation components manually before comparing offers");
    }

    @PutMapping("/users/me/career-memory-preference")
    public Map<String, Boolean> memoryPreference(@Valid @RequestBody WorkspaceRequests.MemoryPreference body) {
        boolean enabled = body.enabled();
        db.jdbc.update("""
                UPDATE user_profiles SET preferences=jsonb_set(preferences,'{useCareerMemory}',to_jsonb(?::boolean)),updated_at=now()
                WHERE user_id=?
                """, enabled, db.uid());
        return Map.of("enabled", enabled);
    }

    @GetMapping("/career-facts")
    public List<Map<String, Object>> facts() {
        log.debug("Entering WorkspaceController.facts");
        return db.list("SELECT * FROM career_facts WHERE user_id=? ORDER BY created_at DESC", db.uid());
    }

    @PostMapping("/career-facts")
    public Map<String, Object> fact(@Valid @RequestBody WorkspaceRequests.Fact body) {
        log.debug("Entering WorkspaceController.fact");
        String text = body.description().trim();
        UUID id = UUID.randomUUID();
        db.jdbc.update("INSERT INTO career_facts(id,user_id,fact_type,description,verified_by_user) VALUES (?,?,?,?,?)", id, db.uid(), text(body.factType()).isEmpty() ? "ACHIEVEMENT" : text(body.factType()), text, body.verifiedByUser());
        return db.one("SELECT * FROM career_facts WHERE id=?", id);
    }

    @GetMapping("/subscription")
    public Map<String, Object> subscription() {
        log.debug("Entering WorkspaceController.subscription");
        return db.one("SELECT plan,status,period_end,provider_customer IS NOT NULL AS has_billing_customer FROM subscriptions WHERE user_id=?", db.uid());
    }

    @PatchMapping("/career-facts/{id}")
    public Map<String, Object> updateFact(@PathVariable UUID id, @Valid @RequestBody WorkspaceRequests.Fact body) {
        String text = body.description().trim();
        if (db.jdbc.update("""
                UPDATE career_facts SET description=?,verified_by_user=? WHERE id=? AND user_id=?
                """, text, body.verifiedByUser(), id, db.uid()) != 1) throw ApiError.notFound();
        return db.one("SELECT * FROM career_facts WHERE id=? AND user_id=?", id, db.uid());
    }

    @DeleteMapping("/career-facts/{id}")
    public ResponseEntity<Void> removeFact(@PathVariable UUID id) {
        if (db.jdbc.update("DELETE FROM career_facts WHERE id=? AND user_id=?", id, db.uid()) != 1) throw ApiError.notFound();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/privacy/data-export")
    public ResponseEntity<Map<String, Object>> dataExport() {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(privacy.snapshot(db.uid()));
    }

    @GetMapping(value = "/privacy/data-export/archive", produces = "application/zip")
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> archive() {
        UUID owner = db.uid();
        var snapshot = privacy.snapshot(owner);
        var files = privacy.files(owner);
        org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody body = output -> {
            try (var zip = new java.util.zip.ZipOutputStream(output)) {
                zip.putNextEntry(new java.util.zip.ZipEntry("careerforge-data.json"));
                zip.write(db.json.writeValueAsBytes(snapshot));
                zip.closeEntry();
                for (var file : files) {
                    String safe = String.valueOf(file.get("filename")).replaceAll("[^A-Za-z0-9._-]", "_");
                    byte[] bytes;
                    try {
                        bytes = docs.read(String.valueOf(file.get("storageKey")), owner);
                    } catch (ApiError e) {
                        zip.putNextEntry(new java.util.zip.ZipEntry("unavailable/" + file.get("id") + ".txt"));
                        zip.write("File was unavailable at export time.".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        zip.closeEntry();
                        continue;
                    }
                    zip.putNextEntry(new java.util.zip.ZipEntry(file.get("folder") + "/" + file.get("id") + "-" + safe));
                    zip.write(bytes);
                    zip.closeEntry();
                }
            }
        };
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .header("Content-Disposition", "attachment; filename=careerforge-data.zip").body(body);
    }

    @DeleteMapping("/users/me")
    public ResponseEntity<Void> deleteUser(@Valid @RequestBody WorkspaceRequests.Confirmation body) {
        if (!"DELETE MY ACCOUNT".equals(body.confirmation()))
            throw ApiError.invalid("Confirmation must equal DELETE MY ACCOUNT");
        privacy.deleteAccount(db.uid());
        return ResponseEntity.noContent().build();
    }
}
