package com.careerforge.core;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

public record CursorPage<T>(List<T> items, String nextCursor) {
    public record Position(Instant time, UUID id) {
        public OffsetDateTime timestamp() { return time.atOffset(ZoneOffset.UTC); }
    }

    public static Position decode(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > 200) throw ApiError.invalid("Invalid pagination cursor");
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII).split("/", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            return new Position(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw ApiError.invalid("Invalid pagination cursor");
        }
    }

    public static int limit(int requested) { return Math.clamp(requested, 1, 100); }

    public static CursorPage<Map<String, Object>> rows(List<Map<String, Object>> rows, int limit, String timeColumn) {
        if (rows.size() <= limit) return new CursorPage<>(rows, null);
        var last = rows.get(limit - 1);
        Object stamp = last.get(timeColumn);
        Instant time = switch (stamp) {
            case java.sql.Timestamp ts -> ts.toInstant();
            case OffsetDateTime odt -> odt.toInstant();
            case Instant instant -> instant;
            default -> throw new IllegalStateException("Missing cursor timestamp");
        };
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (time + "/" + last.get("id")).getBytes(StandardCharsets.US_ASCII));
        return new CursorPage<>(List.copyOf(rows.subList(0, limit)), token);
    }
}
