package com.careerforge.documents;

import com.careerforge.ai.CareerAi;
import com.careerforge.core.ApiError;
import com.careerforge.core.Db;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

@Service
public class ResumeCreation {
    public record Created(UUID resumeId, UUID versionId, Map<String, Object> structuredContent) {}

    private final Db db;
    private final CareerAi ai;
    private final DocumentService docs;
    private final TransactionTemplate tx;

    public ResumeCreation(Db db, CareerAi ai, DocumentService docs, TransactionTemplate tx) {
        this.db = db;
        this.ai = ai;
        this.docs = docs;
        this.tx = tx;
    }

    public Created create(String name, String original, String mime, byte[] raw) {
        if (original.strip().length() < 25 || original.length() > 100000 || original.indexOf(0) >= 0) {
            throw ApiError.invalid("Resume text must be between 25 and 100,000 characters");
        }
        UUID owner = db.uid();
        // Parsing and provider work happen before taking a connection or owner lock.
        Map<String, Object> structure = ai.resume(original);
        return tx.execute(status -> persist(owner, name, original, mime, raw, structure));
    }

    private Created persist(UUID owner, String name, String original, String mime, byte[] raw,
                            Map<String, Object> structure) {
        db.lockUser(owner);
        UUID resume = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        String json = db.encode(structure);
        db.jdbc.update("""
                INSERT INTO resumes(id,user_id,title,original_filename,original_text,content_type,structured_json)
                VALUES (?,?,?,?,?,?,?::jsonb)
                """, resume, owner, name, raw == null ? "pasted text" : name, original, mime, json);
        db.jdbc.update("""
                INSERT INTO resume_versions(id,resume_id,version_number,content_text,content_json)
                VALUES (?,?,1,?,?::jsonb)
                """, version, resume, original, json);
        if (raw != null) {
            String key = docs.storeForTransaction(owner, raw, mime.equals("application/pdf") ? "pdf" : "docx");
            db.jdbc.update("""
                    INSERT INTO documents(id,user_id,resume_version_id,kind,storage_key,media_type,filename,size_bytes,sha256)
                    VALUES (?,?,?,'ORIGINAL',?,?,?,?,?)
                    """, UUID.randomUUID(), owner, version, key, mime, name, raw.length, docs.sha(raw));
        }
        db.audit("RESUME_CREATED", "resume", resume);
        return new Created(resume, version, structure);
    }
}
