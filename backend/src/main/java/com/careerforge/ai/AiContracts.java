package com.careerforge.ai;

import com.careerforge.core.ApiError;

import java.util.*;

public final class AiContracts {
    private AiContracts() {}

    public static List<String> skills(Map<?, ?> resume) {
        return strings(resume.get("skills"), "Skills", 200, 120);
    }

    public static void resume(Map<?, ?> resume) {
        ExecutableSchema.RESUME.validateInput(resume);
        if (!"resume-extract-v1".equals(resume.get("schemaVersion"))) throw ApiError.invalid("Unsupported resume schema version");
        skills(resume);
        if (!(resume.get("candidate") instanceof Map<?, ?> candidate)) throw ApiError.invalid("Candidate details must be an object");
        text(candidate.get("name"), "Name", 200, true);
        text(candidate.get("headline"), "Headline", 300, true);
        if (!(resume.get("experiences") instanceof List<?> experiences) || experiences.size() > 100) {
            throw ApiError.invalid("Experiences must be an array of up to 100 entries");
        }
        for (Object experience : experiences) {
            if (!(experience instanceof Map<?, ?> entry)) throw ApiError.invalid("Invalid experience entry");
            text(entry.get("company"), "Company", 200, true);
            text(entry.get("role"), "Role", 200, true);
            strings(entry.get("achievements"), "Achievements", 100, 2000);
        }
        strings(resume.get("warnings"), "Warnings", 100, 1000);
    }

    public static void job(Map<?, ?> job, String source) {
        ExecutableSchema.JOB.validateInput(job);
        if (!"job-extract-v1".equals(job.get("schemaVersion"))) throw ApiError.invalid("Unsupported job schema version");
        text(job.get("jobTitle"), "Job title", 250, true);
        if (!(job.get("requirements") instanceof List<?> requirements) || requirements.size() > 200) {
            throw ApiError.invalid("Requirements must be an array of up to 200 entries");
        }
        Set<String> ids = new HashSet<>();
        for (Object requirement : requirements) {
            if (!(requirement instanceof Map<?, ?> item)) throw ApiError.invalid("Invalid requirement");
            if (!ids.add(text(item.get("id"), "Requirement ID", 120, false))) throw ApiError.invalid("Requirement IDs must be unique");
            text(item.get("label"), "Requirement label", 250, false);
            if (!(item.get("priority") instanceof String priority) || !Set.of("MANDATORY", "PREFERRED").contains(priority)) {
                throw ApiError.invalid("Requirement priority must be MANDATORY or PREFERRED");
            }
            String quote = text(item.get("sourceQuote"), "Requirement source quote", 2000, false);
            if (!source.toLowerCase(Locale.ROOT).contains(quote.toLowerCase(Locale.ROOT))) {
                throw ApiError.invalid("Each requirement needs a quote from the job description");
            }
        }
        strings(job.get("responsibilities"), "Responsibilities", 150, 2000);
        Object years = job.get("minExperienceYears");
        if (years != null && (!(years instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0 || n.doubleValue() > 80)) {
            throw ApiError.invalid("Invalid minimum years of experience");
        }
    }

    static List<String> strings(Object value, String field, int maxItems, int maxChars) {
        if (!(value instanceof List<?> list) || list.size() > maxItems) throw ApiError.invalid(field + " must be a list");
        return list.stream().map(s -> text(s, field, maxChars, false)).toList();
    }

    static String text(Object value, String field, int maxChars, boolean emptyAllowed) {
        if (!(value instanceof String s) || s.length() > maxChars || (!emptyAllowed && s.isBlank())) {
            throw ApiError.invalid("Invalid " + field);
        }
        return s.strip();
    }
}
