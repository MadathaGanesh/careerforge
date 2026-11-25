package com.careerforge.documents;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JakeRendererTest {
    @TempDir Path directory;

    @Test
    void resolvesMiKTeXWithoutAnUpdatedWindowsPath() throws IOException {
        Path installed = directory.resolve("Programs/MiKTeX/miktex/bin/x64/pdflatex.exe");
        Files.createDirectories(installed.getParent());
        Files.createFile(installed);

        assertEquals(installed.toString(), JakeResumeService.resolveCompiler(
                "pdflatex", true, Map.of("LOCALAPPDATA", directory.toString())));
        Path pathInstallation = Files.createFile(directory.resolve("pdflatex.exe"));
        assertEquals(pathInstallation.toString(), JakeResumeService.resolveCompiler(
                "pdflatex", true, Map.of("Path", directory.toString(), "LOCALAPPDATA", directory.toString())));
    }

    @Test
    void preservesAnExplicitExecutablePathWithSpaces() {
        Path configured = directory.resolve("TeX installation").resolve("pdflatex.exe");
        assertEquals(configured.toString(), JakeResumeService.resolveCompiler(
                "\"" + configured + "\"", true, Map.of("PATH", directory.toString())));
    }

    @Test
    void missingExecutableLeavesSourceExportAvailableAndProvidesSetupGuidance() {
        var service = new JakeResumeService(directory.resolve("missing-pdflatex").toString());
        service.checkRenderer();

        assertFalse(service.capability().jakePdf());
        assertTrue(service.capability().jakePdfMessage().contains("LATEX_COMMAND"));
        assertFalse(service.capability().jakePdfMessage().contains(directory.toString()));
        assertTrue(service.latex("Taylor Example\nExperience\n- Built test software.", "Taylor Example", "").length > 0);
    }

    @Test
    void placesResumeContactsDatedEntriesAndApprovedBulletsInTemplate() {
        var service = new JakeResumeService("pdflatex");
        String content = """
                Taylor Example
                taylor@example.test
                Experience
                Backend Developer | 2022-Present
                - Delivered Java services and automated tests.
                Skills
                Java, Spring Boot
                """;
        String tex = new String(service.latex(content, "", ""), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(tex.contains("\\scshape Taylor Example"));
        assertTrue(tex.contains("taylor@example.test\n\\end{center}"));
        assertTrue(tex.contains("\\textbf{Backend Developer} & 2022-Present"));
        assertTrue(tex.contains("\\item Delivered Java services"));
        assertTrue(tex.contains("\\section{Technical Skills}"));
    }
}
