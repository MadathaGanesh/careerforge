package com.careerforge.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class Db {
    private static final Logger log = LoggerFactory.getLogger(Db.class);

    public final JdbcTemplate jdbc;
    public final ObjectMapper json;

    public Db(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public String fingerprint(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public void lockUser(UUID owner) {
        one("SELECT id FROM app_users WHERE id=? AND deleted_at IS NULL FOR UPDATE", owner);
    }

    public UUID uid() {
        log.debug("Entering Db.uid");
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token))
            throw new ApiError(org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in required");
        return UUID.fromString(token.getToken().getSubject());
    }

    public String encode(Object obj) {
        log.debug("Entering Db.encode");
        try {
            return json.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }

    public Object decode(Object o) {
        log.debug("Entering Db.decode");
        if (o instanceof PGobject p && ("jsonb".equals(p.getType()) || "json".equals(p.getType()))) {
            try {
                return json.readValue(p.getValue(), Object.class);
            } catch (Exception e) {
                throw new IllegalStateException("Stored JSON is invalid", e);
            }
        }
        return o;
    }

    public Map<String, Object> camel(Map<String, Object> row) {
        log.debug("Entering Db.camel");
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> {
            String[] parts = k.split("_");
            StringBuilder key = new StringBuilder(parts[0]);
            for (int i = 1; i < parts.length; i++)
                if (!parts[i].isEmpty())
                    key.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
            out.put(key.toString(), decode(v));
        });
        return out;
    }

    public List<Map<String, Object>> list(String sql, Object... args) {
        log.debug("Entering Db.list");
        return jdbc.queryForList(sql, args).stream().map(this::camel).toList();
    }

    public Map<String, Object> one(String sql, Object... args) {
        log.debug("Entering Db.one");
        try {
            return camel(jdbc.queryForMap(sql, args));
        } catch (EmptyResultDataAccessException e) {
            log.warn("Database lookup returned no rows");
            throw ApiError.notFound();
        }
    }

    public Map<String, Object> owned(String table, UUID id) {
        log.debug("Entering Db.owned");
        if (!Set.of("resumes", "jobs", "analyses", "tailoring_sessions", "applications", "interview_sessions", "exports", "salary_estimates").contains(table))
            throw new IllegalArgumentException("Invalid table");
        return one("SELECT * FROM " + table + " WHERE id=? AND user_id=?", id, uid());
    }

    public void audit(String type, String resource, UUID id) {
        log.debug("Entering Db.audit");
        log.info("Audit event recorded type={} resource={} resourceId={}", type, resource, id);
        jdbc.update("INSERT INTO audit_events (id,user_id,event_type,resource_type,resource_id) VALUES (?,?,?,?,?)", UUID.randomUUID(), uid(), type, resource, id);
    }
}

