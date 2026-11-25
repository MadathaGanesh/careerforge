package com.careerforge.documents;

import com.careerforge.core.ApiError;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

@Service
public class DocumentService {
    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentStorage storage;
    private final String pdfFontPath;
    private final UploadScanner scanner;

    public DocumentService(String root, String font) throws IOException {
        this(font, new UploadScanner("", 3310), new DocumentStorage(root, "", "careerforge/", "us-east-1", "", false));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DocumentService(@Value("${app.pdf-font-path:}") String font,
                           UploadScanner scanner, DocumentStorage storage) {
        this.pdfFontPath = font.strip();
        this.scanner = scanner;
        this.storage = storage;
    }

    public Extracted extract(MultipartFile file) {
        log.debug("Entering DocumentService.extract");
        try {
            byte[] data = file.getBytes();
            if (data.length == 0 || data.length > 10 * 1024 * 1024)
                throw ApiError.invalid("File must be 1 byte to 10 MB");
            String name = Optional.ofNullable(file.getOriginalFilename()).orElse("resume").replace('\\', '/');
            name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
            if (name.length() > 250) throw ApiError.invalid("Filename exceeds 250 characters");
            String type;
            if (data.length >= 4 && new String(data, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1).startsWith("%PDF"))
                type = "application/pdf";
            else if (data.length >= 4 && data[0] == 'P' && data[1] == 'K' && data[2] == 3 && data[3] == 4 && name.toLowerCase(Locale.ROOT).endsWith(".docx"))
                type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            else
                throw new ApiError(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "FILE_UNSUPPORTED", "Only text PDFs and DOCX documents are accepted");
            scanner.scan(data);
            String text;
            if (type.equals("application/pdf")) try (PDDocument doc = Loader.loadPDF(data)) {
                if (doc.getNumberOfPages() > 30) throw ApiError.invalid("Resume exceeds 30 pages");
                text = new PDFTextStripper().getText(doc);
            }
            else try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data))) {
                StringBuilder sb = new StringBuilder();
                for (XWPFParagraph p : doc.getParagraphs()) sb.append(p.getText()).append('\n');
                for (XWPFTable table : doc.getTables())
                    for (XWPFTableRow row : table.getRows())
                        for (XWPFTableCell cell : row.getTableCells()) sb.append(cell.getText()).append(' ');
                text = sb.toString();
            }
            if (text.strip().length() < 25)
                throw ApiError.invalid("No extractable text found; paste your resume text instead");
            return new Extracted(text, type, name, data);
        } catch (ApiError e) {
            log.warn("Document validation failed code={}", e.code);
            throw e;
        } catch (Exception e) {
            log.error("Document extraction failed exceptionType={}", e.getClass().getSimpleName());
            throw ApiError.invalid("Document could not be parsed safely");
        }
    }

    public byte[] read(String key, UUID owner) { return storage.read(owner, key); }
    public void purge(UUID owner) { storage.purge(owner); }

    public String sha(byte[] b) {
        log.debug("Entering DocumentService.sha");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] docx(String content) {
        log.debug("Entering DocumentService.docx");
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            String[] lines = content.replace("\r", "").split("\n", -1);
            for (String line : lines) {
                XWPFParagraph p = doc.createParagraph();
                p.setSpacingAfter(80);
                XWPFRun run = p.createRun();
                run.setFontFamily("Arial");
                run.setFontSize(10);
                run.setText(line.isBlank() ? " " : line);
            }
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("DOCX export failed", e);
        }
    }

    public byte[] pdf(String content) {
        log.debug("Entering DocumentService.pdf");
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType0Font font = loadPdfFont(doc);
            PDPage page = null;
            PDPageContentStream stream = null;
            float y = 0;
            for (String para : content.replace("\r", "").split("\n", -1)) {
                String safe = para.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ").replace("\t", "    ");
                List<String> lines;
                try {
                    lines = wrap(safe, font, 505);
                } catch (IllegalArgumentException e) {
                    throw ApiError.invalid("The PDF font cannot render some characters. Export DOCX or configure a font that supports them.");
                }
                for (String line : lines) {
                    if (stream == null || y < 55) {
                        if (stream != null) stream.close();
                        page = new PDPage(PDRectangle.A4);
                        doc.addPage(page);
                        stream = new PDPageContentStream(doc, page);
                        stream.setFont(font, 10);
                        y = 790;
                    }
                    stream.beginText();
                    stream.newLineAtOffset(45, y);
                    stream.showText(line);
                    stream.endText();
                    y -= 14;
                }
                y -= 4;
            }
            if (stream != null) stream.close();
            if (doc.getNumberOfPages() == 0) doc.addPage(new PDPage(PDRectangle.A4));
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("PDF export failed", e);
        }
    }

    private PDType0Font loadPdfFont(PDDocument document) {
        try {
            Path fontFile = pdfFontPath.isBlank() ? defaultPdfFont() : Path.of(pdfFontPath);
            if (Files.isRegularFile(fontFile)) {
                try (InputStream in = Files.newInputStream(fontFile)) {
                    return PDType0Font.load(document, in);
                }
            }
        } catch (IOException | InvalidPathException e) {
            log.warn("Standard PDF font could not be loaded category={}", e.getClass().getSimpleName());
        }
        throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "PDF_FONT_UNAVAILABLE",
                "Standard PDF needs a readable TrueType font. Set PDF_FONT_PATH to an installed .ttf file or export DOCX.");
    }

    static Path defaultPdfFont() {
        if (File.separatorChar != '\\') return defaultPdfFont(null);
        String root = System.getenv("SystemRoot");
        return defaultPdfFont(root == null || root.isBlank() ? System.getenv("WINDIR") : root);
    }

    static Path defaultPdfFont(String windowsRoot) {
        if (windowsRoot != null && !windowsRoot.isBlank()) {
            Path fonts = Path.of(windowsRoot).resolve("Fonts");
            for (String name : List.of("arial.ttf", "segoeui.ttf")) {
                Path candidate = fonts.resolve(name);
                if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) return candidate;
            }
            return fonts.resolve("arial.ttf");
        }
        return Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf");
    }

    static List<String> wrap(String line, PDType0Font font, float maxWidth) throws IOException {
        if (line.isEmpty()) return List.of(" ");
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < line.length()) {
            int end = start;
            int space = -1;
            float width = 0;
            while (end < line.length()) {
                int next = end + Character.charCount(line.codePointAt(end));
                float glyph = font.getStringWidth(line.substring(end, next)) / 100f;
                if (end > start && width + glyph > maxWidth) break;
                if (line.charAt(end) == ' ' && end > start) space = end;
                width += glyph;
                end = next;
            }
            if (end < line.length() && space > start) {
                out.add(line.substring(start, space));
                start = space + 1;
            } else {
                out.add(line.substring(start, end));
                start = end;
            }
        }
        return out;
    }

    public void remove(String key, UUID owner) { storage.delete(owner, key); }

    public String storeForTransaction(UUID owner, byte[] bytes, String extension) {
        return storage.putInTransaction(owner, bytes, extension);
    }

    public record Extracted(String text, String mime, String name, byte[] original) {
    }
}

