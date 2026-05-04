package com.pdfreplace;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

@Service
public class PdfReplaceService {
    public ReplacementOutput replace(
            MultipartFile pdf,
            String search,
            String replacement,
            boolean strict,
            MultipartFile fallbackFont
    ) throws IOException {
        validate(pdf, search);

        Path workDir = Files.createTempDirectory("pdf-replacer-");
        Path input = workDir.resolve("input.pdf");
        Path output = workDir.resolve("output.pdf");
        Path fontPath = null;

        try {
            copyUpload(pdf, input);
            ensureLooksLikePdf(input);

            File fontFile = null;
            if (fallbackFont != null && !fallbackFont.isEmpty()) {
                validateFont(fallbackFont);
                fontPath = workDir.resolve("fallback-font.ttf");
                copyUpload(fallbackFont, fontPath);
                fontFile = fontPath.toFile();
            }

            DeterministicPdfReplacer.Result result = DeterministicPdfReplacer.replace(
                    input.toFile(),
                    output.toFile(),
                    search,
                    replacement == null ? "" : replacement,
                    strict,
                    fontFile
            );

            if (result.matchesReplaced() == 0) {
                throw new IllegalArgumentException("No matching text was found in the PDF.");
            }

            String outputName = outputName(pdf.getOriginalFilename());
            return new ReplacementOutput(outputName, Files.readAllBytes(output), result);
        } finally {
            deleteIfExists(fontPath);
            deleteIfExists(output);
            deleteIfExists(input);
            deleteIfExists(workDir);
        }
    }

    private static void validate(MultipartFile pdf, String search) {
        if (pdf == null || pdf.isEmpty()) {
            throw new IllegalArgumentException("Upload a PDF file.");
        }
        if (search == null || search.isBlank()) {
            throw new IllegalArgumentException("Search text is required.");
        }
        String filename = pdf.getOriginalFilename();
        if (filename != null && !filename.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("Only PDF files are supported.");
        }
    }

    private static void copyUpload(MultipartFile upload, Path target) throws IOException {
        try (InputStream input = upload.getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void ensureLooksLikePdf(Path input) throws IOException {
        byte[] header;
        try (InputStream stream = Files.newInputStream(input)) {
            header = stream.readNBytes(5);
        }
        if (header.length < 5 || header[0] != '%' || header[1] != 'P' || header[2] != 'D' || header[3] != 'F' || header[4] != '-') {
            throw new IllegalArgumentException("The uploaded file does not look like a valid PDF.");
        }
    }

    private static void validateFont(MultipartFile font) {
        String filename = font.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Fallback font must be a .ttf or .otf file.");
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".ttf") && !lower.endsWith(".otf")) {
            throw new IllegalArgumentException("Fallback font must be a .ttf or .otf file.");
        }
    }

    private static String outputName(String originalName) {
        String base = originalName == null || originalName.isBlank() ? "document" : originalName;
        base = Path.of(base).getFileName().toString();
        base = base.replaceAll("[\\r\\n\\\\/]+", "_");
        base = base.replaceFirst("(?i)\\.pdf$", "");
        if (base.isBlank()) {
            base = "document";
        }
        return base + "-replaced.pdf";
    }

    private static void deleteIfExists(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Temporary files are best-effort cleanup.
        }
    }

    public record ReplacementOutput(String filename, byte[] bytes, DeterministicPdfReplacer.Result result) {
    }
}
