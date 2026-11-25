package com.careerforge.documents;

import com.careerforge.core.ApiError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DocumentServiceTest {
    @TempDir Path directory;

    @Test
    void detectsInstalledWindowsFonts() throws Exception {
        Path fonts = Files.createDirectory(directory.resolve("Fonts"));
        Path segoe = Files.createFile(fonts.resolve("segoeui.ttf"));
        assertEquals(segoe, DocumentService.defaultPdfFont(directory.toString()));

        Path arial = Files.createFile(fonts.resolve("arial.ttf"));
        assertEquals(arial, DocumentService.defaultPdfFont(directory.toString()));
    }

    @Test
    void unusableFontReturnsActionableErrorAndKeepsDocxAvailable() throws Exception {
        Path badFont = directory.resolve("bad-font.ttf");
        var service = new DocumentService(directory.toString(), badFont.toString());

        for (int attempt = 0; attempt < 2; attempt++) {
            var error = assertThrows(ApiError.class, () -> service.pdf("Example resume."));
            assertEquals("PDF_FONT_UNAVAILABLE", error.code);
            assertEquals(503, error.status.value());
            assertTrue(error.getMessage().contains("PDF_FONT_PATH"));
            if (attempt == 0) Files.writeString(badFont, "Not a TrueType font.");
        }
        assertTrue(service.docx("Example resume.").length > 100);
    }

    @Test
    void storageProtectsOwnerAndReadsLegacyFiles() throws Exception {
        var storage = new DocumentStorage(directory.toString(), "", "careerforge/", "us-east-1", "", false);
        UUID owner = UUID.randomUUID(), another = UUID.randomUUID();
        byte[] contents = new byte[]{1, 2, 3};
        String key = storage.put(owner, contents, "pdf");
        assertArrayEquals(contents, storage.read(owner, key));
        assertThrows(ApiError.class, () -> storage.read(another, key));
        Path legacy = directory.resolve(owner.toString()).resolve(UUID.randomUUID() + ".pdf");
        Files.write(legacy, contents);
        assertArrayEquals(contents, storage.read(owner, legacy.toString()));
        storage.purge(owner);
        assertFalse(Files.exists(legacy));
        assertThrows(ApiError.class, () -> storage.read(owner, key));
    }
}
