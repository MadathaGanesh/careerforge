package com.careerforge.career;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Deterministic resume-readiness estimates.
 * <p>
 * These are NOT official scores from an ATS vendor,
 * recruiter or hiring manager.
 */
@Service
public class ResumeScoringService {

    private static final Logger log =
            LoggerFactory.getLogger(ResumeScoringService.class);

    private static final String VERSION =
            "careerforge-evidence-rubric-v2";

    private static final Pattern EMAIL =
            Pattern.compile(
                    "(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}"
            );

    private static final Pattern METRIC =
            Pattern.compile(
                    "(?i)(?<![a-z0-9])\\d+(?:[.,]\\d+)?\\s*"
                            + "(?:%|percent|ms|users|customers|projects"
                            + "|requests|hours|days|k|m|million)"
                            + "(?![a-z0-9])"
            );

    private static final Pattern ACTION =
            Pattern.compile(
                    "(?i)\\b(?:built|developed|designed"
                            + "|implemented|led|improved|optimized"
                            + "|delivered|maintained|created"
                            + "|automated|reduced|increased)\\b"
            );

    // =====================================================
    // COMPARE ORIGINAL AND TAILORED RESUME
    // =====================================================

    public Map<String, Object> compare(
            String original,
            String tailored,
            Map<String, Object> jd,
            List<Map<String, Object>> approved
    ) {

        long started = System.nanoTime();

        Map<String, Object> safeJd =
                jd == null ? Map.of() : jd;

        List<Map<String, Object>> safeApproved =
                approved == null ? List.of() : approved;

        log.debug(
                "Resume comparison started rubric={} originalChars={} tailoredChars={} approvedEdits={}",
                VERSION,
                original == null ? 0 : original.length(),
                tailored == null ? 0 : tailored.length(),
                safeApproved.size()
        );

        try {

            Map<String, Object> before =
                    metrics(original, safeJd);

            Map<String, Object> after =
                    metrics(tailored, safeJd);

            Map<String, Integer> deltas =
                    new LinkedHashMap<>();

            for (String metric :
                    List.of(
                            "ats",
                            "recruiter",
                            "hiringManager"
                    )) {

                int beforeScore =
                        ((Number) before.get(metric))
                                .intValue();

                int afterScore =
                        ((Number) after.get(metric))
                                .intValue();

                deltas.put(
                        metric,
                        afterScore - beforeScore
                );
            }

            List<Map<String, Object>> explanations =
                    explainChanges(
                            safeApproved,
                            safeJd
                    );

            Map<String, Object> report =
                    new LinkedHashMap<>();

            report.put(
                    "rubricVersion",
                    VERSION
            );

            report.put(
                    "scoreKind",
                    "HEURISTIC_ESTIMATE_NOT_VENDOR_ATS"
            );

            report.put("original", before);
            report.put("tailored", after);
            report.put("deltas", deltas);
            report.put("topChanges", explanations);

            report.put(
                    "acceptedEditCount",
                    safeApproved.size()
            );

            report.put(
                    "disclaimer",
                    "These are CareerForge heuristic readiness "
                            + "scores (0-100), not measurements "
                            + "from an employer ATS, a recruiter, "
                            + "or a hiring manager. The scores "
                            + "do not predict interviews or hiring. "
                            + "Improvement is not guaranteed. "
                            + "Always confirm that the tailored "
                            + "resume is accurate."
            );

            log.info(
                    "Resume comparison scored rubric={} beforeATS={} afterATS={} beforeRecruiter={} afterRecruiter={} beforeHiring={} afterHiring={} changeCount={} durationMs={}",
                    VERSION,
                    before.get("ats"),
                    after.get("ats"),
                    before.get("recruiter"),
                    after.get("recruiter"),
                    before.get("hiringManager"),
                    after.get("hiringManager"),
                    explanations.size(),
                    elapsedMs(started)
            );

            log.debug(
                    "Score deltas ats={} recruiter={} hiringManager={}",
                    deltas.get("ats"),
                    deltas.get("recruiter"),
                    deltas.get("hiringManager")
            );

            return report;

        } catch (RuntimeException exception) {

            log.error(
                    "Resume comparison failed category={} rubric={} durationMs={}",
                    exception.getClass().getSimpleName(),
                    VERSION,
                    elapsedMs(started)
            );

            throw exception;
        }
    }

    // =====================================================
    // CALCULATE INDIVIDUAL SCORE COMPONENTS
    // =====================================================

    private Map<String, Object> metrics(
            String text,
            Map<String, Object> jd
    ) {

        String source =
                text == null ? "" : text;

        double keywordCoverage =
                keywordCoverage(source, jd);

        int contact =
                EMAIL.matcher(source).find()
                        ? 100
                        : 0;

        int section =
                (containsAny(
                        source,
                        "experience",
                        "work history",
                        "employment"
                ) ? 40 : 0)
                        + (containsAny(
                        source,
                        "skills",
                        "technologies",
                        "technical skills"
                ) ? 35 : 0)
                        + (containsAny(
                        source,
                        "education",
                        "projects",
                        "certifications"
                ) ? 25 : 0);

        int legibility =
                source.isBlank()
                        ? 0
                        : source.length() <= 12000
                          ? 100
                          : 65;

        int action =
                ACTION.matcher(source).find()
                        ? 100
                        : 0;

        int impact =
                METRIC.matcher(source).find()
                        ? 100
                        : 0;

        int concise =
                source.length() < 100
                        || source.length() > 18000
                        ? 40
                        : 100;

        int supportingEvidence = 0;

        if (containsAny(
                source,
                "experience",
                "employment",
                "worked",
                "developer",
                "engineer"
        )) {
            supportingEvidence += 40;
        }

        if (containsAny(
                source,
                "project",
                "built",
                "developed",
                "implemented",
                "designed"
        )) {
            supportingEvidence += 35;
        }

        if (containsAny(
                source,
                "architecture",
                "performance",
                "impact",
                "result",
                "delivered"
        )) {
            supportingEvidence += 25;
        }

        int ats = bounded(
                0.60 * keywordCoverage
                        + 0.20 * section
                        + 0.10 * contact
                        + 0.10 * legibility
        );

        int recruiter = bounded(
                0.45 * keywordCoverage
                        + 0.20 * section
                        + 0.20 * action
                        + 0.15 * concise
        );

        int hiring = bounded(
                0.40 * keywordCoverage
                        + 0.25 * supportingEvidence
                        + 0.20 * impact
                        + 0.15 * action
        );

        Map<String, Object> components =
                new LinkedHashMap<>();

        components.put(
                "jobRequirementCoverage",
                bounded(keywordCoverage)
        );

        components.put(
                "recognizedSections",
                section
        );

        components.put(
                "contactDetected",
                contact
        );

        components.put(
                "actionLanguage",
                action
        );

        components.put(
                "quantifiedImpactDetected",
                impact
        );

        components.put(
                "experienceEvidenceSignals",
                supportingEvidence
        );

        log.debug(
                "Resume score components coverage={} sections={} contact={} action={} impact={} evidence={}",
                bounded(keywordCoverage),
                section,
                contact,
                action,
                impact,
                supportingEvidence
        );

        return Map.of(
                "ats", ats,
                "recruiter", recruiter,
                "hiringManager", hiring,
                "components", components
        );
    }

    // =====================================================
    // MATCH RESUME AGAINST JD REQUIREMENTS
    // =====================================================

    private double keywordCoverage(
            String text,
            Map<String, Object> jd
    ) {

        Object requirements =
                jd.get("requirements");

        if (!(requirements instanceof List<?> list)) {

            log.debug(
                    "No structured JD requirements available"
            );

            return 0;
        }

        double weights = 0;
        double found = 0;

        int validRequirementCount = 0;
        int matchedRequirementCount = 0;

        for (Object item : list) {

            if (!(item instanceof Map<?, ?> requirement)) {
                continue;
            }

            Object raw =
                    requirement.get("label");

            String label =
                    raw == null
                            ? ""
                            : raw.toString().trim();

            if (label.isBlank()
                    || label.length() > 150) {
                continue;
            }

            validRequirementCount++;

            double weight =
                    "PREFERRED".equalsIgnoreCase(
                            String.valueOf(
                                    requirement.get("priority")
                            )
                    ) ? 1 : 2;

            weights += weight;

            if (hasPhrase(text, label)) {

                found += weight;
                matchedRequirementCount++;
            }
        }

        if (weights == 0) {

            log.debug(
                    "JD has no supported requirements; keyword coverage=0"
            );

            return 0;
        }

        double coverage =
                found * 100 / weights;

        log.debug(
                "JD keyword coverage calculated validRequirements={} matchedRequirements={} coverage={}",
                validRequirementCount,
                matchedRequirementCount,
                bounded(coverage)
        );

        return coverage;
    }

    private boolean hasPhrase(
            String text,
            String phrase
    ) {

        return Pattern.compile(
                        "(?i)(?<![a-z0-9])"
                                + Pattern.quote(phrase)
                                + "(?![a-z0-9])"
                )
                .matcher(text)
                .find();
    }

    private boolean containsAny(
            String text,
            String... tokens
    ) {

        String lower =
                text.toLowerCase(Locale.ROOT);

        for (String token : tokens) {

            if (lower.contains(token)) {
                return true;
            }
        }

        return false;
    }

    private int bounded(double number) {

        return (int) Math.max(
                0,
                Math.min(
                        100,
                        Math.round(number)
                )
        );
    }

    // =====================================================
    // EXPLAIN UP TO FIVE APPROVED CHANGES
    // =====================================================

    private List<Map<String, Object>> explainChanges(
            List<Map<String, Object>> approved,
            Map<String, Object> jd
    ) {

        List<Map<String, Object>> output =
                new ArrayList<>();

        Object requirements =
                jd.get("requirements");

        for (Map<String, Object> edit : approved) {

            if (output.size() == 5) {
                break;
            }

            String oldText =
                    String.valueOf(
                            edit.getOrDefault(
                                    "originalText",
                                    ""
                            )
                    );

            String newText =
                    String.valueOf(
                            edit.getOrDefault(
                                    "proposedText",
                                    ""
                            )
                    );

            String reason =
                    String.valueOf(
                            edit.getOrDefault(
                                    "reason",
                                    "Improved presentation of a supported claim"
                            )
                    );

            if (oldText.equals(newText)) {
                continue;
            }

            List<String> newlyExplicit =
                    new ArrayList<>();

            if (requirements instanceof List<?> list) {

                for (Object obj : list) {

                    if (!(obj instanceof Map<?, ?> r)
                            || r.get("label") == null) {
                        continue;
                    }

                    String skill =
                            r.get("label").toString();

                    if (!hasPhrase(oldText, skill)
                            && hasPhrase(newText, skill)) {

                        newlyExplicit.add(skill);
                    }
                }
            }

            Map<String, Object> item =
                    new LinkedHashMap<>();

            item.put("reason", reason);

            item.put(
                    "originalText",
                    truncate(oldText)
            );

            item.put(
                    "tailoredText",
                    truncate(newText)
            );

            item.put(
                    "newlyExplicitJdTerms",
                    newlyExplicit.stream()
                            .distinct()
                            .limit(5)
                            .toList()
            );

            item.put(
                    "impactNote",
                    newlyExplicit.isEmpty()
                            ? "Edit accepted by the user. "
                              + "Any score change is based only "
                              + "on the defined heuristic; "
                              + "this edit may not increase it."
                            : "The edited text makes job "
                              + "terminology explicit. Confirm "
                              + "the terminology is supported "
                              + "by your actual experience."
            );

            output.add(item);
        }

        if (output.isEmpty()) {

            output.add(
                    Map.of(
                            "reason",
                            "No material approved changes have been applied",

                            "impactNote",
                            "The compared resumes contain the same content; no improvement should be claimed.",

                            "originalText",
                            "",

                            "tailoredText",
                            "",

                            "newlyExplicitJdTerms",
                            List.of()
                    )
            );
        }

        log.debug(
                "Resume improvement explanations generated count={} approvedEdits={}",
                output.size(),
                approved.size()
        );

        return output;
    }

    private String truncate(String text) {

        return text.length() <= 300
                ? text
                : text.substring(0, 300) + "...";
    }

    private long elapsedMs(long started) {

        return TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started
        );
    }
}

