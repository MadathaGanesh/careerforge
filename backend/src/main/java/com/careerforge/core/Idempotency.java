package com.careerforge.core;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.function.Supplier;

@Service
public class Idempotency {
    private final Db db;

    public Idempotency(Db db) {
        this.db = db;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> run(String operation, String key, String input, Supplier<Map<String, Object>> create) {
        if (key == null || key.isBlank()) return create.get();
        if (!key.matches("[A-Za-z0-9_.:-]{1,128}")) throw ApiError.invalid("Invalid Idempotency-Key");
        var owner = db.uid();
        db.lockUser(owner);
        String fingerprint = db.fingerprint(input);
        var previous = db.list("""
                SELECT request_hash,response FROM idempotent_requests
                WHERE user_id=? AND operation=? AND request_key=?
                """, owner, operation, key);
        if (!previous.isEmpty()) {
            var row = previous.getFirst();
            if (!fingerprint.equals(row.get("requestHash"))) throw ApiError.conflict("This request key was used with different inputs");
            return (Map<String, Object>) row.get("response");
        }
        Map<String, Object> response = create.get();
        db.jdbc.update("""
                INSERT INTO idempotent_requests(user_id,operation,request_key,request_hash,response)
                VALUES (?,?,?,?,?::jsonb)
                """, owner, operation, key, fingerprint, db.encode(response));
        return response;
    }

    @Scheduled(fixedDelayString = "PT1H")
    public void cleanup() {
        db.jdbc.update("DELETE FROM idempotent_requests WHERE created_at < now()-interval '1 day'");
    }
}
