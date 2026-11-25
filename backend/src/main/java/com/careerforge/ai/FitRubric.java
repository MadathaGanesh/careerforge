package com.careerforge.ai;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FitRubric {
    enum Category {
        MANDATORY(35), EXPERIENCE(25), RESPONSIBILITIES(20), PREFERRED(10), EDUCATION(10);
        final int weight;
        Category(int weight) { this.weight = weight; }
    }

    enum Status {
        DEMONSTRATED(1), PARTIAL(.5), UNVERIFIED(0), MISSING(0);
        final double credit;
        Status(double credit) { this.credit = credit; }
    }

    record Evidence(String sourcePath, int start, int end, String excerpt) {}
    record Assessment(String requirementId, String label, String priority, Category category,
                      Status status, List<Evidence> evidence, String rationale) {}
    record CategoryScore(Category category, double weight, double score) {}

    private static final List<List<String>> ALIASES = List.of(
            List.of("PostgreSQL", "Postgres"), List.of("Kubernetes", "K8s"),
            List.of("React", "React.js", "ReactJS"), List.of("Node.js", "NodeJS"),
            List.of("JavaScript", "ECMAScript"), List.of("CI/CD", "continuous integration and delivery", "continuous integration and deployment"),
            List.of("AWS", "Amazon Web Services"), List.of("GCP", "Google Cloud Platform", "Google Cloud"),
            List.of("REST APIs", "RESTful APIs", "REST API"), List.of("JUnit", "JUnit 5"));
    private static final Set<String> STOP = Set.of("the", "and", "with", "from", "into", "will", "must", "your",
            "that", "this", "have", "able", "for", "our", "you", "role");
    private static final Pattern YEARS = Pattern.compile(
            "(?i)(?<![\\d.-])(\\d{1,2}(?:\\.\\d{1,2})?)\\s*\\+?\\s*years?(?:\\s+of)?\\s+(?:relevant\\s+|professional\\s+|total\\s+)?experience");
    private static final Pattern NEGATION = Pattern.compile("(?i)\\b(?:no|without|lack(?:s|ing)?|never|not)\\b[^,;.!?]{0,45}$");
    private static final Pattern LEARNING = Pattern.compile("(?i)\\b(?:learning|studying|familiar with|exposure to|course in|currently exploring)\\b[^,;.!?]{0,45}$");

    private FitRubric() {}

    static Map<String, Object> assess(Map<String, Object> job, String resume) {
        var evaluations = new ArrayList<Assessment>();
        if (job.get("requirements") instanceof List<?> requirements) {
            for (Object item : requirements) {
                if (!(item instanceof Map<?, ?> requirement) || !(requirement.get("label") instanceof String label) || label.isBlank()) continue;
                String id = value(requirement, "id", "req-" + evaluations.size());
                String priority = "PREFERRED".equals(requirement.get("priority")) ? "PREFERRED" : "MANDATORY";
                Category category = category(requirement, label, priority);
                evaluations.add(textMatch(id, label, priority, category, resume));
            }
        }
        if (job.get("minExperienceYears") instanceof Number minimum && minimum.doubleValue() > 0
                && evaluations.stream().noneMatch(a -> a.category() == Category.EXPERIENCE)) {
            evaluations.add(experience(minimum.doubleValue(), resume));
        }
        if (job.get("responsibilities") instanceof List<?> responsibilities) {
            int sequence = 0;
            for (Object item : responsibilities) {
                if (!(item instanceof String task) || task.isBlank()) continue;
                String label = task.length() > 250 ? task.substring(0, 250).stripTrailing() : task;
                if (evaluations.stream().anyMatch(a -> a.label().equalsIgnoreCase(label))) continue;
                evaluations.add(responsibility("responsibility-" + ++sequence, label, resume));
            }
        }

        var breakdown = new ArrayList<CategoryScore>();
        var excluded = new ArrayList<Category>();
        EnumMap<Category, List<Assessment>> groups = new EnumMap<>(Category.class);
        for (Assessment e : evaluations) groups.computeIfAbsent(e.category(), key -> new ArrayList<>()).add(e);
        int appliedWeight = groups.keySet().stream().mapToInt(c -> c.weight).sum();
        double total = 0;
        for (Category category : Category.values()) {
            var group = groups.get(category);
            if (group == null) { excluded.add(category); continue; }
            double weight = 100.0 * category.weight / appliedWeight;
            double score = group.stream().mapToDouble(a -> a.status().credit * 100).average().orElse(0);
            total += weight * score / 100;
            breakdown.add(new CategoryScore(category, round(weight), round(score)));
        }
        var result = Map.<String, Object>of("rubricVersion", "fit-v3", "alignmentScore", round(total),
                "scoreKind", "HEURISTIC_ESTIMATE", "requirements", evaluations,
                "categoryBreakdown", breakdown, "excludedCategories", excluded, "mode", "LOCAL_HEURISTIC",
                "disclaimer", "Resume evidence is compared against the reviewed job requirements. The score is an estimate and does not predict hiring or ATS outcomes.");
        ExecutableSchema.FIT.validateInput(result);
        return result;
    }

    private static Assessment textMatch(String id, String label, String priority, Category category, String resume) {
        List<String> phrases = ALIASES.stream().filter(group -> group.stream().anyMatch(label::equalsIgnoreCase))
                .findFirst().orElseGet(() -> List.of(label));
        boolean weak = false;
        for (String phrase : phrases) {
            Matcher match = word(phrase).matcher(resume);
            while (match.find()) {
                String context = clause(resume, match.start());
                if (NEGATION.matcher(context).find()) continue;
                if (LEARNING.matcher(context).find()) { weak = true; continue; }
                return new Assessment(id, label, priority, category, Status.DEMONSTRATED,
                        List.of(evidence(resume, match.start(), match.end())), "Reviewed resume contains direct text evidence.");
            }
        }
        return new Assessment(id, label, priority, category, weak ? Status.UNVERIFIED : Status.MISSING, List.of(),
                weak ? "The resume mentions study or exposure. Confirm applied experience independently." : "No supporting text was found in the reviewed resume.");
    }

    private static Assessment responsibility(String id, String label, String resume) {
        Assessment exact = textMatch(id, label, "MANDATORY", Category.RESPONSIBILITIES, resume);
        if (exact.status() == Status.DEMONSTRATED) return exact;
        var terms = Arrays.stream(label.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(t -> t.length() > 3 && !STOP.contains(t)).distinct().limit(15).toList();
        if (terms.size() < 2) return exact;
        for (Matcher segment = Pattern.compile("[^\\n]+").matcher(resume); segment.find();) {
            String line = segment.group();
            long supported = terms.stream().filter(t -> {
                Matcher match = word(t).matcher(line);
                return match.find() && !NEGATION.matcher(clause(line, match.start())).find()
                        && !LEARNING.matcher(clause(line, match.start())).find();
            }).count();
            if (supported >= 2 && supported * 2 >= terms.size()) {
                return new Assessment(id, label, "MANDATORY", Category.RESPONSIBILITIES, Status.PARTIAL,
                        List.of(evidence(resume, segment.start(), Math.min(segment.end(), segment.start() + 250))),
                        "A resume line shares several responsibility terms. Review the scope and context.");
            }
        }
        return exact;
    }

    private static Assessment experience(double minimum, String resume) {
        Matcher match = YEARS.matcher(resume);
        double best = 0;
        Evidence source = null;
        while (match.find()) {
            if (NEGATION.matcher(clause(resume, match.start())).find()) continue;
            double years = Double.parseDouble(match.group(1));
            if (years <= best) continue;
            best = years;
            source = evidence(resume, match.start(), match.end());
        }
        Status status = best >= minimum ? Status.DEMONSTRATED : best > 0 ? Status.PARTIAL : Status.UNVERIFIED;
        return new Assessment("minimum-experience", "At least " + minimum + " years of experience",
                "MANDATORY", Category.EXPERIENCE, status, source == null ? List.of() : List.of(source),
                source == null ? "The resume does not state a total. Dates and role-specific experience need review."
                        : "Compared with the duration explicitly stated in the reviewed resume.");
    }

    private static Category category(Map<?, ?> requirement, String label, String priority) {
        if (requirement.get("category") instanceof String inferred) {
            try { return Category.valueOf(inferred); } catch (IllegalArgumentException ignored) { }
        }
        if (label.matches("(?i).*\\b(?:degree|bachelor|master|certification|diploma|phd|certificate)\\b.*")) return Category.EDUCATION;
        return "PREFERRED".equals(priority) ? Category.PREFERRED : Category.MANDATORY;
    }

    private static Pattern word(String phrase) {
        return Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(phrase) + "(?![\\p{L}\\p{N}])");
    }

    private static String clause(String resume, int start) {
        return resume.substring(Math.max(0, start - 80), start).replaceFirst("(?s)^.*[.!?;\\n]", "");
    }

    private static Evidence evidence(String text, int start, int end) {
        int from = Math.max(0, start - 48), to = Math.min(text.length(), end + 65);
        if (from > 0 && Character.isLowSurrogate(text.charAt(from))) from--;
        if (to < text.length() && Character.isLowSurrogate(text.charAt(to))) to++;
        return new Evidence("/contentText", from, to, text.substring(from, to));
    }

    private static String value(Map<?, ?> obj, String name, String fallback) {
        return obj.get(name) instanceof String s && !s.isBlank() ? s : fallback;
    }

    private static double round(double value) { return Math.round(100 * value) / 100.0; }
}
