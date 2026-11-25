package com.careerforge.documents;

import com.careerforge.core.ApiError;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DocumentCorpusTest {
    @TempDir Path directory;
    private DocumentService documents;

    @BeforeEach
    void setUp() throws Exception {
        documents = new DocumentService(directory.toString(), "");
    }

    @Test
    void preservesLatinGreekCyrillicAndContentAfterPageBreaks() throws Exception {
        String first = "Café résumé · Εμπειρία · Привет";
        String last = "LAST PAGE SENTINEL";
        String content = first + "\n" + "Worked on a project that improved service reliability.\n".repeat(145) + last;
        try (var file = Loader.loadPDF(documents.pdf(content))) {
            assertTrue(file.getNumberOfPages() > 1);
            var text = new PDFTextStripper().getText(file);
            assertTrue(text.contains(first));
            assertTrue(text.stripTrailing().endsWith(last));
        }
    }

    @Test
    void extractsTableCellsFromDocx() throws Exception {
        try (var document = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            var table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("Built services using Spring Boot");
            table.getRow(0).getCell(1).setText("PostgreSQL");
            document.write(bytes);
            var upload = new MockMultipartFile("file", "table.docx", "", bytes.toByteArray());
            var extracted = documents.extract(upload);
            assertTrue(extracted.text().contains("Spring Boot"));
            assertTrue(extracted.text().contains("PostgreSQL"));
        }
    }

    @Test
    void rejectsImageOnlyOrEmptyPdfAndMalformedDocxWithActionableErrors() throws Exception {
        try (var pdf = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage());
            pdf.save(bytes);
            var upload = new MockMultipartFile("file", "no-text.pdf", "application/pdf", bytes.toByteArray());
            assertTrue(assertThrows(ApiError.class, () -> documents.extract(upload)).getMessage().contains("paste"));
        }
        var malformed = new MockMultipartFile("file", "broken.docx", "", new byte[]{'P', 'K', 3, 4, 0});
        assertEquals(422, assertThrows(ApiError.class, () -> documents.extract(malformed)).status.value());
    }

    @Test
    void rejectsUnsupportedFontCoverageAndOffersDocxAlternative() {
        assertTrue(assertThrows(ApiError.class, () -> documents.pdf("Candidate 𠀀")).getMessage().contains("DOCX"));
        assertTrue(documents.docx("Candidate 𠀀").length > 100);
    }
}
