package com.careerforge.documents;

import com.careerforge.core.ApiError;
import java.util.*;

public final class ReviewedEdits {
    private ReviewedEdits() {}

    public record Result(String text, List<Map<String, Object>> approved, int pending) {
        public void requireComplete() {
            if (pending > 0) throw ApiError.conflict("Accept or reject all proposed edits before exporting");
        }
    }
    private record Replacement(int start, int end, String text) {}

    public static Result apply(String original, List<Map<String, Object>> proposals) {
        var approved = new ArrayList<Map<String, Object>>();
        var replacements = new ArrayList<Replacement>();
        int pending = 0;
        String fingerprint = null;
        for (var proposal : proposals) {
            if ("PENDING".equals(proposal.get("decision"))) pending++;
            if (!"ACCEPTED".equals(proposal.get("decision"))) continue;
            String source = (String) proposal.get("originalText");
            int start;
            int end;
            if (proposal.get("sourceStart") instanceof Number from && proposal.get("sourceEnd") instanceof Number to) {
                start = from.intValue();
                end = to.intValue();
                if (fingerprint == null) fingerprint = fingerprint(original);
                if (!fingerprint.equals(proposal.get("sourceSha256")) || start < 0 || end > original.length()
                        || end <= start || !original.substring(start, end).equals(source)) {
                    throw ApiError.conflict("An accepted edit refers to different source content. Review the proposal again.");
                }
            } else {
                start = original.indexOf(source);
                end = start + source.length();
                if (source.isEmpty() || start < 0 || original.indexOf(source, start + 1) >= 0) {
                    throw ApiError.conflict("An accepted edit needs a unique match in its source resume. Reject it and review the other edits.");
                }
            }
            replacements.add(new Replacement(start, end, (String) proposal.get("proposedText")));
            approved.add(proposal);
        }
        replacements.sort(Comparator.comparingInt(Replacement::start));
        StringBuilder result = new StringBuilder();
        int cursor = 0;
        for (Replacement replacement : replacements) {
            if (replacement.start < cursor) throw ApiError.conflict("Accepted edits overlap. Reject one of the overlapping edits.");
            result.append(original, cursor, replacement.start).append(replacement.text);
            cursor = replacement.end;
        }
        result.append(original, cursor, original.length());
        if (result.length() > 100000) throw ApiError.invalid("The revised resume exceeds 100,000 characters");
        return new Result(result.toString(), List.copyOf(approved), pending);
    }
    public static String fingerprint(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
