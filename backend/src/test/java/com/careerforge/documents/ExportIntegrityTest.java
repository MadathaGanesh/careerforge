package com.careerforge.documents;

import com.careerforge.core.ApiError;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExportIntegrityTest {
    @TempDir Path directory;

    @Test
    void standardPdfPreservesEveryCharacterAcrossForcedWraps() throws Exception {
        var service = new DocumentService(directory.toString(), "");
        String token = "W".repeat(100) + "X" + "B".repeat(21);
        try (var pdf = Loader.loadPDF(service.pdf(token))) {
            assertEquals(token, new PDFTextStripper().getText(pdf).replaceAll("\\s+", ""));
        }
    }

    @Test
    void jakeTexKeepsLateFactsAndEscapesContent() {
        var service = new JakeResumeService("pdflatex");
        String text = "Candidate\nExperience\n" + "Reviewed line\n".repeat(250) + "Final evidence & unique outcome";
        String latex = new String(service.latex(text, "Candidate", ""), StandardCharsets.UTF_8);
        assertTrue(latex.contains("Final evidence \\& unique outcome"));
    }

    @Test
    void rejectsOverlapAndAmbiguousMatchesInsteadOfSkippingEdits() {
        var a = edit("second third", "one");
        var b = edit("third fourth", "two");
        assertThrows(ApiError.class, () -> ReviewedEdits.apply("first second third fourth", List.of(a, b)));
        assertThrows(ApiError.class, () -> ReviewedEdits.apply("second third then second third", List.of(a)));
    }

    @Test
    void appliesIndependentEditsAgainstOriginalOffsets() {
        var result = ReviewedEdits.apply("first second third fourth", List.of(edit("fourth", "last"), edit("first", "begin")));
        assertEquals("begin second third last", result.text());
    }

    @Test
    void preventsApplyingAnEditToDifferentSourceContent() {
        String text = "first second third";
        var proposal = new java.util.HashMap<>(edit("second", "changed"));
        proposal.put("sourceStart", 6);
        proposal.put("sourceEnd", 12);
        proposal.put("sourceSha256", ReviewedEdits.fingerprint(text));
        assertEquals("first changed third", ReviewedEdits.apply(text, List.of(proposal)).text());
        assertThrows(ApiError.class, () -> ReviewedEdits.apply(text + " other", List.of(proposal)));
    }

    private Map<String, Object> edit(String from, String to) {
        return Map.of("originalText", from, "proposedText", to, "decision", "ACCEPTED");
    }
}
