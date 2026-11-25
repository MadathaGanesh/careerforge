package com.careerforge.documents;

import com.careerforge.core.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Generates Jake's Resume-style LaTeX documents.
 * <p>
 * Based on Jake Gutierrez's Resume (MIT License).
 * https://github.com/jakegut/resume
 * <p>
 * Never print resume contents or personal information in logs.
 * Never enable LaTeX shell escape for untrusted documents.
 */
@Service
public class JakeResumeService {

    private static final Logger log =
            LoggerFactory.getLogger(JakeResumeService.class);

    private final String compiler;
    private final java.util.concurrent.Semaphore rendererSlots = new java.util.concurrent.Semaphore(2);
    private volatile PdfCapability capability = new PdfCapability(false, "The Jake PDF renderer is starting.");

    public record PdfCapability(boolean jakePdf, String jakePdfMessage) {}

    public JakeResumeService(
            @Value("${app.latex-command:pdflatex}") String compiler
    ) {
        this.compiler = resolveCompiler(compiler, File.separatorChar == '\\', System.getenv());

        log.info("JakeResumeService initialized");
    }

    static String resolveCompiler(String configured, boolean windows, Map<String, String> environment) {
        String command = configured.strip();
        if (command.startsWith("\"") && command.endsWith("\"") && command.length() > 1) {
            command = command.substring(1, command.length() - 1);
        }
        if (command.isBlank()) command = "pdflatex";
        if (!windows || !Set.of("pdflatex", "pdflatex.exe").contains(command.toLowerCase(Locale.ROOT))) {
            return command;
        }

        Map<String, String> windowsEnvironment = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        windowsEnvironment.putAll(environment);
        for (String directory : windowsEnvironment.getOrDefault("PATH", "").split(";")) {
            Path executable = installedCompiler(directory.strip().replace("\"", ""), "pdflatex.exe");
            if (executable != null) return executable.toString();
        }
        for (String key : List.of("LOCALAPPDATA", "ProgramFiles", "ProgramW6432")) {
            String location = key.equals("LOCALAPPDATA") ? "Programs/MiKTeX" : "MiKTeX";
            Path executable = installedCompiler(windowsEnvironment.get(key), location + "/miktex/bin/x64/pdflatex.exe");
            if (executable != null) return executable.toString();
        }
        return command;
    }

    private static Path installedCompiler(String directory, String relativePath) {
        if (directory == null || directory.isBlank()) return null;
        try {
            Path path = Path.of(directory);
            if (!path.isAbsolute()) return null;
            Path executable = path.resolve(relativePath);
            return Files.isRegularFile(executable) ? executable : null;
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    void checkRenderer() {
        try {
            pdf(latex("CareerForge Renderer\nExperience\n- Export capability test.", "CareerForge Renderer", ""));
            capability = new PdfCapability(true, null);
        } catch (ApiError error) {
            String detail = "LATEX_RENDER_FAILED".equals(error.code)
                    ? "PDFLaTeX started but could not compile the test document. Update the TeX installation and install the required packages."
                    : error.getMessage();
            capability = new PdfCapability(false, detail);
            log.warn("Jake PDF unavailable at startup code={} guidance={}", error.code, detail);
        } catch (RuntimeException error) {
            capability = new PdfCapability(false, "The Jake PDF startup check failed. Check the backend log.");
            log.warn("Unexpected Jake PDF startup check failure category={}", error.getClass().getSimpleName());
        }
    }

    public PdfCapability capability() {
        return capability;
    }

    // ======================================================
    // GENERATE LATEX SOURCE
    // ======================================================

    public byte[] latex(
            String content,
            String candidateName,
            String registeredEmail
    ) {

        long started = System.nanoTime();

        log.debug(
                "Jake LaTeX generation requested contentCharacters={}",
                content == null ? 0 : content.length()
        );

        if (content == null
                || content.isBlank()
                || content.length() > 100_000) {

            log.warn(
                    "Jake LaTeX generation rejected reason=INVALID_CONTENT"
            );

            throw ApiError.invalid(
                    "Resume content missing or too long"
            );
        }

        try {

            String normalized = normalize(content);

            List<String> lines = Arrays.stream(
                            normalized
                                    .replace("\r", "")
                                    .split("\n")
                    )
                    .map(String::strip)
                    .filter(x -> !x.isBlank())
                    .toList();
            if (lines.isEmpty()) throw ApiError.invalid("Resume content is missing");
            String name = headerName(candidateName, lines);

            List<String> contacts =
                    headerContacts(
                            lines,
                            name,
                            registeredEmail
                    );

            Map<String, List<String>> sections =
                    splitSections(
                            lines,
                            name,
                            contacts
                    );

            StringBuilder latex = new StringBuilder();

            latex.append(
                    "% Based on Jake's Resume by Jake Gutierrez "
                            + "(c) 2020, MIT License.\n"
            );

            latex.append(
                    "% Original: https://github.com/jakegut/resume\n"
            );

            latex.append(
                    "\\documentclass[letterpaper,11pt]{article}\n"
            );

            latex.append(
                    "\\usepackage[empty]{fullpage}\n"
            );

            latex.append(
                    "\\usepackage{titlesec}\n"
            );

            latex.append(
                    "\\usepackage[hidelinks]{hyperref}\n"
            );

            latex.append(
                    "\\usepackage{enumitem}\n"
            );

            latex.append(
                    "\\usepackage{tabularx}\n"
            );

            latex.append(
                    "\\usepackage[T1]{fontenc}\n"
            );

            latex.append(
                    "\\usepackage{lmodern}\n"
            );

            latex.append(
                    "\\input{glyphtounicode}\n"
            );

            latex.append(
                    "\\pdfgentounicode=1\n"
            );

            latex.append(
                    "\\addtolength{\\oddsidemargin}{-0.5in}\n"
            );

            latex.append(
                    "\\addtolength{\\evensidemargin}{-0.5in}\n"
            );

            latex.append(
                    "\\addtolength{\\textwidth}{1in}\n"
            );

            latex.append(
                    "\\addtolength{\\topmargin}{-0.5in}\n"
            );

            latex.append(
                    "\\addtolength{\\textheight}{1.0in}\n"
            );

            latex.append(
                    "\\setlength{\\parindent}{0pt}\n"
            );
            latex.append("\\setlength{\\emergencystretch}{2em}\n");

            latex.append(
                    "\\titleformat{\\section}"
                            + "{\\vspace{-4pt}\\scshape"
                            + "\\raggedright\\large}"
                            + "{}{0em}{}"
                            + "[\\titlerule \\vspace{-5pt}]\n"
            );

            latex.append("\\begin{document}\n");
            latex.append("\\begin{center}\n");

            latex.append(
                    "{\\Huge \\scshape "
            );

            latex.append(
                    escape(name)
            );

            latex.append(
                    "}\\\\ \\vspace{3pt}\n"
            );

            latex.append("\\small ");

            latex.append(
                    String.join(
                            " \\textbar{} ",
                            contacts.stream()
                                    .map(this::escape)
                                    .toList()
                    )
            );

            latex.append("\n\\end{center}\n");

            for (var section : sections.entrySet()) {

                latex.append("\\section{");

                latex.append(
                        escape(section.getKey())
                );

                latex.append("}\n");

                boolean listOpen = false;

                for (String line : section.getValue()) {

                    String trimmed = line.strip();

                    boolean bullet =
                            trimmed.matches(
                                    "^[-*\\u2022]\\s+.+"
                            );

                    if (bullet) {

                        if (!listOpen) {

                            latex.append(
                                    "\\begin{itemize}"
                                            + "[leftmargin=0.20in,"
                                            + "itemsep=0pt,"
                                            + "topsep=0pt]\n"
                            );

                            listOpen = true;
                        }

                        latex.append("\\item ");

                        latex.append(
                                escape(
                                        trimmed.replaceFirst(
                                                "^[-*\\u2022]\\s+",
                                                ""
                                        )
                                )
                        );

                        latex.append("\n");

                    } else {

                        if (listOpen) {

                            latex.append(
                                    "\\end{itemize}\n"
                            );

                            listOpen = false;
                        }

                        latex.append(
                                "\\noindent\\small "
                        );

                        latex.append(
                                entryHeading(section.getKey(), trimmed)
                        );

                        latex.append("\\par\n");
                    }
                }

                if (listOpen) {
                    latex.append(
                            "\\end{itemize}\n"
                    );
                }
            }

            latex.append("\\end{document}\n");

            byte[] output =
                    latex.toString()
                            .getBytes(
                                    StandardCharsets.UTF_8
                            );

            log.info(
                    "Jake LaTeX generated sections={} lines={} bytes={} durationMs={}",
                    sections.size(),
                    lines.size(),
                    output.length,
                    elapsedMs(started)
            );

            return output;

        } catch (RuntimeException exception) {

            log.error(
                    "Jake LaTeX generation failed category={} durationMs={}",
                    exception.getClass().getSimpleName(),
                    elapsedMs(started)
            );

            throw exception;
        }
    }

    // ======================================================
    // EXTRACT CONTACT INFORMATION
    // ======================================================

    private String headerName(String confirmedName, List<String> lines) {
        if (confirmedName != null && !confirmedName.isBlank()) return confirmedName.strip();
        String first = lines.getFirst();
        if (recognizedHeading(first) == null && first.length() <= 90
                && first.matches("[\\p{L}][\\p{L}.'-]*(?: +[\\p{L}][\\p{L}.'-]*){1,5}")) {
            return first;
        }
        return "Candidate";
    }

    private String entryHeading(String section, String line) {
        if (!Set.of("Experience", "Education", "Projects").contains(section)) return escape(line);
        String[] parts = line.split("\\s*\\|\\s*", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank() || parts[1].length() > 32
                || !parts[1].matches("(?i).*(?:\\b(?:19|20)\\d{2}\\b|\\bpresent\\b).*")) return escape(line);
        return "\\begin{tabularx}{\\linewidth}{@{}X r@{}}\\textbf{" + escape(parts[0])
                + "} & " + escape(parts[1]) + "\\end{tabularx}";
    }

    private List<String> headerContacts(
            List<String> lines,
            String name,
            String email
    ) {

        Set<String> fields =
                new LinkedHashSet<>();

        if (email != null && !email.isBlank()) {

            fields.add(email.strip());
        }

        for (int i = 0;
             i < Math.min(6, lines.size());
             i++) {

            String line = lines.get(i);

            if (recognizedHeading(line) != null) {
                break;
            }

            boolean contact =
                    line.matches("(?i)^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
                            || line.equalsIgnoreCase(email == null ? "" : email)
                            || line.matches(
                            "(?i)^.*(?:linkedin\\.com"
                                    + "|github\\.com"
                                    + "|https?://).*$"
                    )
                            || line.matches(
                            "(?i)^[+()0-9\\s-]{8,22}$"
                    );

            if (contact
                    && !line.equalsIgnoreCase(name)) {

                fields.add(line);
            }
        }

        return new ArrayList<>(fields);
    }

    // ======================================================
    // SPLIT RESUME INTO SECTIONS
    // ======================================================

    private Map<String, List<String>> splitSections(
            List<String> lines,
            String name,
            List<String> contacts
    ) {

        Map<String, List<String>> result =
                new LinkedHashMap<>();

        String section = "Profile";

        result.put(
                section,
                new ArrayList<>()
        );

        for (int i = 0; i < lines.size(); i++) {

            String line = lines.get(i);

            if (i == 0
                    && line.equalsIgnoreCase(
                    name.strip()
            )) {
                continue;
            }

            if (i < 6
                    && contacts.stream()
                    .anyMatch(
                            c -> c.equalsIgnoreCase(line)
                    )) {
                continue;
            }

            String heading =
                    recognizedHeading(line);

            if (heading != null) {

                section = heading;

                result.computeIfAbsent(
                        section,
                        k -> new ArrayList<>()
                );

                continue;
            }

            result.get(section).add(line);
        }

        result.entrySet().removeIf(
                e -> e.getValue().isEmpty()
        );

        if (result.isEmpty()) {

            result.put(
                    "Profile",
                    List.of(
                            "See source resume for verified "
                                    + "experience details"
                    )
            );
        }

        return result;
    }

    // ======================================================
    // RECOGNIZED RESUME HEADINGS
    // ======================================================

    private String recognizedHeading(String line) {

        String heading =
                line.toLowerCase(Locale.ROOT)
                        .replace(":", "")
                        .strip();

        return switch (heading) {

            case "summary",
                 "professional summary",
                 "career objective",
                 "objective" -> "Professional Summary";

            case "experience",
                 "work experience",
                 "professional experience",
                 "employment",
                 "work history" -> "Experience";

            case "skills",
                 "technical skills",
                 "skills and technologies",
                 "technologies" -> "Technical Skills";

            case "projects",
                 "project experience",
                 "key projects" -> "Projects";

            case "education",
                 "academic qualifications" -> "Education";

            case "certifications",
                 "certificates" -> "Certifications";

            case "achievements",
                 "awards" -> "Achievements";

            default -> null;
        };
    }

    // ======================================================
    // ESCAPE LATEX CONTROL CHARACTERS
    // ======================================================

    public String escape(String value) {

        StringBuilder output =
                new StringBuilder();

        String safe =
                normalize(value == null ? "" : value);

        for (int i = 0; i < safe.length(); i++) {

            char character = safe.charAt(i);

            switch (character) {

                case '\\' -> output.append(
                        "\\textbackslash{}"
                );

                case '&' -> output.append("\\&");

                case '%' -> output.append("\\%");

                case '$' -> output.append("\\$");

                case '#' -> output.append("\\#");

                case '_' -> output.append("\\_");

                case '{' -> output.append("\\{");

                case '}' -> output.append("\\}");

                case '~' -> output.append(
                        "\\textasciitilde{}"
                );

                case '^' -> output.append(
                        "\\textasciicircum{}"
                );

                default -> output.append(character);
            }
        }

        return output.toString();
    }

    // ======================================================
    // NORMALIZE RESUME TEXT
    // ======================================================

    private String normalize(String source) {

        String text = source
                .replace('\u2013', '-')
                .replace('\u2014', '-')
                .replace('\u2018', '\'')
                .replace('\u2019', '\'')
                .replace('\u201C', '"')
                .replace('\u201D', '"')
                .replace('\u00A0', ' ')
                .replace('\u2022', '*');

        StringBuilder output =
                new StringBuilder();

        for (int i = 0; i < text.length(); i++) {

            char character = text.charAt(i);

            if (character == '\n'
                    || character == '\r'
                    || character == '\t'
                    || (character >= 32
                    && character <= 126)) {

                output.append(character);

            } else {

                // Preserve for editable TEX output.
                // Some Unicode glyphs may require
                // further adjustment in PDFLaTeX.
                output.append(character);
            }
        }

        return output.toString();
    }

    // ======================================================
    // COMPILE LATEX TO PDF
    // ======================================================

    public byte[] pdf(byte[] latex) {
        if (!rendererSlots.tryAcquire()) throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "LATEX_BUSY", "PDF renderer is busy. Try again.");
        try {
            return compile(latex);
        } finally {
            rendererSlots.release();
        }
    }

    private byte[] compile(byte[] latex) {

        long started = System.nanoTime();

        if (latex == null || latex.length == 0) {

            log.warn(
                    "Jake PDF compilation rejected reason=EMPTY_LATEX"
            );

            throw ApiError.invalid(
                    "LaTeX source is empty"
            );
        }

        log.info(
                "Jake PDF compilation started latexBytes={}",
                latex.length
        );

        Path work = null;
        Process process = null;

        try {

            work = Files.createTempDirectory(
                    "careerforge-latex-"
            );

            Path tex =
                    work.resolve("resume.tex");

            Path compilerOutputFile =
                    work.resolve(
                            "pdflatex-process.log"
                    );

            Files.write(
                    tex,
                    latex,
                    StandardOpenOption.CREATE_NEW
            );

            var builder = new ProcessBuilder(
                            compiler,
                            "--disable-write18",
                            "--interaction=nonstopmode",
                            "--halt-on-error",
                            tex.getFileName().toString()
                    )
                            .directory(work.toFile())
                            .redirectErrorStream(true)
                            .redirectOutput(
                                    compilerOutputFile.toFile()
                            );
            builder.environment().put("openin_any", "p");
            builder.environment().put("openout_any", "p");
            try {
                process = builder.start();
            } catch (IOException | IllegalArgumentException e) {
                log.warn("Unable to start PDFLaTeX category={} durationMs={}",
                        e.getClass().getSimpleName(), elapsedMs(started));
                throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "LATEX_UNAVAILABLE",
                        "PDFLaTeX could not start. Install MiKTeX or TeX Live, or set LATEX_COMMAND to the full executable path. Restart the backend.");
            }

            boolean completed =
                    process.waitFor(
                            25,
                            TimeUnit.SECONDS
                    );

            if (!completed) {

                process.destroyForcibly();

                log.warn(
                        "Jake PDF compilation timed out durationMs={}",
                        elapsedMs(started)
                );

                throw new ApiError(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "LATEX_TIMEOUT",
                        "Resume PDF renderer timed out"
                );
            }

            Path output =
                    work.resolve("resume.pdf");

            boolean pdfPresent =
                    Files.exists(output);

            if (process.exitValue() != 0
                    || !pdfPresent) {

                log.warn(
                        "Jake PDF compiler failed exitCode={} pdfPresent={} durationMs={}",
                        process.exitValue(),
                        pdfPresent,
                        elapsedMs(started)
                );

                throw new ApiError(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "LATEX_RENDER_FAILED",
                        "Could not render this resume in LaTeX; "
                                + "download the .tex file and review "
                                + "unsupported characters or content"
                );
            }

            long size = Files.size(output);
            if (size == 0 || size > 10 * 1024 * 1024) {

                log.error(
                        "Jake PDF output size invalid bytes={}",
                        size
                );

                throw new ApiError(HttpStatus.UNPROCESSABLE_ENTITY, "LATEX_RENDER_FAILED",
                        "The renderer produced a PDF outside the supported size.");
            }

            byte[] result = Files.readAllBytes(output);
            log.info(
                    "Jake PDF compiled bytes={} durationMs={}",
                    result.length,
                    elapsedMs(started)
            );

            return result;

        } catch (IOException exception) {

            log.error(
                    "Jake PDF temporary-file access failed category={} durationMs={}",
                    exception.getClass().getSimpleName(),
                    elapsedMs(started)
            );

            throw new ApiError(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "LATEX_WORKSPACE_UNAVAILABLE",
                    "The backend could not use its temporary folder for Jake PDF. Check available disk space and write permissions."
            );

        } catch (InterruptedException exception) {

            Thread.currentThread().interrupt();

            log.warn(
                    "Jake PDF compilation interrupted durationMs={}",
                    elapsedMs(started)
            );

            throw new ApiError(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "LATEX_INTERRUPTED",
                    "Resume PDF rendering interrupted"
            );

        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();

            if (work != null) {

                try (var paths = Files.walk(work)) {

                    paths.sorted(
                            Comparator.reverseOrder()
                    ).forEach(path -> {

                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            log.debug(
                                    "Jake temporary-file cleanup incomplete"
                            );
                        }
                    });

                } catch (IOException ignored) {

                    log.debug(
                            "Jake temporary-directory cleanup incomplete"
                    );
                }
            }
        }
    }

    private long elapsedMs(long started) {

        return TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started
        );
    }
}

