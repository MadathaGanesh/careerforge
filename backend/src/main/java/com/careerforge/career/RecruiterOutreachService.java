package com.careerforge.career;

import com.careerforge.core.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates recruiter outreach drafts.
 * <p>
 * Does not send email.
 * The user must review the draft and send it
 * from their own email application.
 */
@Service
public class RecruiterOutreachService {

    private static final Logger log =
            LoggerFactory.getLogger(
                    RecruiterOutreachService.class
            );

    private static final Pattern EMAIL =
            Pattern.compile(
                    "(?i)(?<![a-z0-9._%+\\-])"
                            + "[a-z0-9._%+\\-]+"
                            + "@[a-z0-9.\\-]+\\.[a-z]{2,}"
                            + "(?![a-z0-9._%+\\-])"
            );

    private static final Pattern EVIDENCE_ACTION =
            Pattern.compile(
                    "(?i)\\b(?:developed|built|designed"
                            + "|delivered|implemented|led"
                            + "|optimized|maintained|automated)\\b"
            );

    // =====================================================
    // GENERATE RECRUITER EMAIL DRAFT
    // =====================================================

    public Map<String, Object> draft(
            String jd,
            String role,
            String company,
            String candidateName,
            String registeredEmail,
            String tailoredText,
            Map<String, Object> parsedJob,
            String suggestedRecipient
    ) {

        long started = System.nanoTime();

        String safeJd = jd == null ? "" : jd;
        String safeTailored =
                tailoredText == null ? "" : tailoredText;

        Map<String, Object> safeParsedJob =
                parsedJob == null ? Map.of() : parsedJob;

        boolean manualRecipient =
                suggestedRecipient != null
                        && !suggestedRecipient.isBlank();

        log.debug(
                "Recruiter outreach preparation started hasManualRecipient={} jdChars={} tailoredChars={}",
                manualRecipient,
                safeJd.length(),
                safeTailored.length()
        );

        try {

            Set<String> addresses =
                    new LinkedHashSet<>();

            Matcher matcher =
                    EMAIL.matcher(safeJd);

            while (matcher.find()
                    && addresses.size() < 10) {

                String foundEmail =
                        matcher.group()
                                .replaceAll(
                                        "[.,;:]$",
                                        ""
                                )
                                .toLowerCase(
                                        Locale.ROOT
                                );

                if (registeredEmail == null
                        || !foundEmail.equalsIgnoreCase(
                        registeredEmail
                )) {

                    addresses.add(foundEmail);
                }
            }

            String recipient =
                    suggestedRecipient == null
                            ? ""
                            : suggestedRecipient.strip();

            if (recipient.isBlank()
                    && !addresses.isEmpty()) {

                recipient =
                        addresses.iterator().next();
            }

            if (!recipient.isBlank()
                    && !EMAIL.matcher(recipient).matches()) {

                log.warn(
                        "Recruiter draft validation failed reason=INVALID_RECIPIENT"
                );

                throw ApiError.invalid(
                        "Enter a valid recruiter email address"
                );
            }

            if (recipient.contains("\r")
                    || recipient.contains("\n")) {

                throw ApiError.invalid(
                        "Invalid recipient"
                );
            }

            String position =
                    role == null || role.isBlank()
                            ? "the advertised role"
                            : role.strip();

            String employer =
                    company == null || company.isBlank()
                            ? "your organization"
                            : company.strip();

            List<String> relevantSkills =
                    relevantSkills(
                            safeTailored,
                            safeParsedJob
                    );

            String skills =
                    relevantSkills.isEmpty()
                            ? "the responsibilities described in the role"
                            : String.join(
                            ", ",
                            relevantSkills
                    );

            List<String> evidenceBullets =
                    shortEvidence(
                            safeTailored,
                            relevantSkills
                    );

            String evidenceBlock =
                    evidenceBullets.isEmpty()
                            ? ""
                            : "A few relevant examples "
                              + "from my resume include:\n"
                              + String.join(
                            "\n",
                            evidenceBullets.stream()
                                    .map(e -> "- " + e)
                                    .toList()
                    )
                              + "\n\n";

            String name =
                    candidateName == null
                            || candidateName.isBlank()
                            ? "Candidate"
                            : candidateName.strip();

            String subject =
                    "Application for "
                            + position
                            + (
                            company == null
                                    || company.isBlank()
                                    ? ""
                                    : " at " + employer
                    );

            String body =
                    "Hello,\n\n"
                            + "I am writing to express interest "
                            + "in the "
                            + position
                            + " opportunity at "
                            + employer
                            + ".\n\n"
                            + "My resume includes experience "
                            + "relevant to "
                            + skills
                            + ". I would welcome the chance "
                            + "to discuss how my background "
                            + "aligns with the requirements "
                            + "of this role.\n\n"
                            + evidenceBlock
                            + "I have prepared a role-specific "
                            + "resume and can include it with "
                            + "this application. "
                            + "Thank you for considering "
                            + "my application.\n\n"
                            + "Best regards,\n"
                            + name
                            + "\n"
                            + (
                            registeredEmail == null
                                    ? ""
                                    : registeredEmail
                    );

            Map<String, Object> output =
                    new LinkedHashMap<>();

            output.put("to", recipient);

            output.put(
                    "suggestedRecipients",
                    new ArrayList<>(addresses)
            );

            output.put(
                    "registeredEmail",
                    registeredEmail == null
                            ? ""
                            : registeredEmail
            );

            output.put("subject", subject);
            output.put("body", body);

            output.put(
                    "notice",
                    "Draft only: CareerForge does not send this message. "
                            + "Your email provider decides the sending account. "
                            + "Verify the recipient and manually attach your "
                            + "tailored PDF before clicking Send."
            );

            log.info(
                    "Recruiter draft prepared detectedRecipientCount={} manualRecipient={} relevantSkills={} evidenceItems={} durationMs={}",
                    addresses.size(),
                    manualRecipient,
                    relevantSkills.size(),
                    evidenceBullets.size(),
                    elapsedMs(started)
            );

            return output;

        } catch (RuntimeException exception) {

            log.error(
                    "Recruiter draft generation failed category={} durationMs={}",
                    exception.getClass().getSimpleName(),
                    elapsedMs(started)
            );

            throw exception;
        }
    }

    // =====================================================
    // EXTRACT SHORT EVIDENCE FROM RESUME
    // =====================================================

    private List<String> shortEvidence(
            String text,
            List<String> relevant
    ) {

        List<String> bullets =
                new ArrayList<>();

        for (String line : text.split("\\R")) {

            String value =
                    line.strip()
                            .replaceFirst(
                                    "^[-*\\u2022]\\s*",
                                    ""
                            );

            if (value.length() < 25
                    || value.length() > 260) {

                continue;
            }

            if (!EVIDENCE_ACTION.matcher(value).find()) {
                continue;
            }

            boolean relevantSkill =
                    relevant.isEmpty();

            for (String skill : relevant) {

                if (value.toLowerCase(Locale.ROOT)
                        .contains(
                                skill.toLowerCase(
                                        Locale.ROOT
                                )
                        )) {

                    relevantSkill = true;
                }
            }

            if (relevantSkill
                    && !bullets.contains(value)) {

                bullets.add(value);
            }

            if (bullets.size() == 2) {
                break;
            }
        }

        log.debug(
                "Recruiter supporting evidence selected count={}",
                bullets.size()
        );

        return bullets;
    }

    // =====================================================
    // EXTRACT RELEVANT SKILLS
    // =====================================================

    private List<String> relevantSkills(
            String tailoredText,
            Map<String, Object> jd
    ) {

        Object requirements =
                jd.get("requirements");

        List<String> result =
                new ArrayList<>();

        if (requirements instanceof List<?> list) {

            for (Object item : list) {

                if (!(item instanceof Map<?, ?> entry)
                        || entry.get("label") == null) {

                    continue;
                }

                String skill =
                        entry.get("label").toString();

                if (skill.length() > 70
                        || skill.isBlank()) {

                    continue;
                }

                if (Pattern.compile(
                                "(?i)(?<![a-z0-9])"
                                        + Pattern.quote(skill)
                                        + "(?![a-z0-9])"
                        )
                        .matcher(tailoredText)
                        .find()
                        && !result.contains(skill)) {

                    result.add(skill);
                }

                if (result.size() >= 3) {
                    break;
                }
            }
        }

        log.debug(
                "Relevant recruiter-email skills matched count={}",
                result.size()
        );

        return result;
    }

    private long elapsedMs(long started) {

        return TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started
        );
    }
}

