package com.pdfreplace;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class PdfReplaceService {
    @Value("${pdfreplacer.limits.max-pages:250}")
    private int maxPages;

    @Value("${pdfreplacer.limits.max-search-length:300}")
    private int maxSearchLength;

    @Value("${pdfreplacer.limits.max-replacement-length:300}")
    private int maxReplacementLength;

    @Value("${pdfreplacer.limits.max-files:10}")
    private int maxFiles;

    @Value("${pdfreplacer.limits.max-file-size-bytes:26214400}")
    private long maxFileSizeBytes;

    @Value("${pdfreplacer.limits.max-total-upload-bytes:104857600}")
    private long maxTotalUploadBytes;

    @Value("${pdfreplacer.limits.max-total-pages:1000}")
    private int maxTotalPages;

    public BatchReplacementOutput replaceBatch(
            MultipartFile[] pdfFiles,
            List<String> searchList,
            List<String> replacementList,
            boolean strict,
            String matchModeRaw,
            String replaceScopeRaw,
            Integer occurrenceIndex,
            boolean preserveStyle,
            MultipartFile fallbackFont
    ) throws IOException {
        validateBatch(pdfFiles, searchList, replacementList, replaceScopeRaw, occurrenceIndex);
        DeterministicPdfReplacer.MatchMode matchMode = parseMatchMode(matchModeRaw);
        DeterministicPdfReplacer.ReplaceScope replaceScope = parseReplaceScope(replaceScopeRaw);
        List<ReplacementRule> rules = buildRules(searchList, replacementList);

        List<ReplacementOutput> outputs = new ArrayList<>();
        DeterministicPdfReplacer.Result summary = new DeterministicPdfReplacer.Result(0, 0, 0, 0, occurrenceIndex, 0, 0);
        int totalPagesProcessed = 0;
        for (MultipartFile pdf : pdfFiles) {
            ReplacementOutput item = replaceSingle(pdf, rules, strict, matchMode, replaceScope, occurrenceIndex, preserveStyle, fallbackFont);
            outputs.add(item);
            totalPagesProcessed += item.result().pagesScanned();
            if (totalPagesProcessed > maxTotalPages) {
                throw new IllegalArgumentException("Total pages across uploaded files exceed the limit of " + maxTotalPages + ".");
            }
            summary = new DeterministicPdfReplacer.Result(
                    summary.pagesScanned() + item.result().pagesScanned(),
                    summary.matchesFound() + item.result().matchesFound(),
                    summary.matchesReplaced() + item.result().matchesReplaced(),
                    summary.segmentsChanged() + item.result().segmentsChanged(),
                    occurrenceIndex,
                    summary.stylePreservedCount() + item.result().stylePreservedCount(),
                    summary.fallbackStyleCount() + item.result().fallbackStyleCount()
            );
        }

        if (outputs.size() == 1) {
            ReplacementOutput only = outputs.get(0);
            return new BatchReplacementOutput(only.filename(), only.bytes(), "application/pdf", summary);
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Set<String> usedNames = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (ReplacementOutput output : outputs) {
                String safeName = uniqueZipName(safeFilename(output.filename()), usedNames);
                zip.putNextEntry(new ZipEntry(safeName));
                zip.write(output.bytes());
                zip.closeEntry();
            }
        }
        return new BatchReplacementOutput("replaced-pdfs.zip", bytes.toByteArray(), "application/zip", summary);
    }

    private ReplacementOutput replaceSingle(
            MultipartFile pdf,
            List<ReplacementRule> rules,
            boolean strict,
            DeterministicPdfReplacer.MatchMode matchMode,
            DeterministicPdfReplacer.ReplaceScope replaceScope,
            Integer occurrenceIndex,
            boolean preserveStyle,
            MultipartFile fallbackFont
    ) throws IOException {
        Path workDir = Files.createTempDirectory("pdf-replacer-");
        Path input = workDir.resolve("input.pdf");
        Path stageInput = input;
        Path stageOutput = null;
        Path fontPath = null;

        try {
            copyUpload(pdf, input);
            ensureLooksLikePdf(input);
            enforcePageLimit(input);

            File fontFile = null;
            if (fallbackFont != null && !fallbackFont.isEmpty()) {
                validateFont(fallbackFont);
                fontPath = workDir.resolve("fallback-font.ttf");
                copyUpload(fallbackFont, fontPath);
                fontFile = fontPath.toFile();
            }

            DeterministicPdfReplacer.Result aggregate = new DeterministicPdfReplacer.Result(0, 0, 0, 0, occurrenceIndex, 0, 0);
            for (int i = 0; i < rules.size(); i++) {
                ReplacementRule rule = rules.get(i);
                stageOutput = workDir.resolve("output-" + i + ".pdf");
                DeterministicPdfReplacer.Result result = DeterministicPdfReplacer.replace(
                        stageInput.toFile(),
                        stageOutput.toFile(),
                        rule.search(),
                        rule.replacement(),
                        strict,
                        fontFile,
                        matchMode,
                        replaceScope,
                        occurrenceIndex,
                        preserveStyle
                );
                aggregate = new DeterministicPdfReplacer.Result(
                        aggregate.pagesScanned() + result.pagesScanned(),
                        aggregate.matchesFound() + result.matchesFound(),
                        aggregate.matchesReplaced() + result.matchesReplaced(),
                        aggregate.segmentsChanged() + result.segmentsChanged(),
                        occurrenceIndex,
                        aggregate.stylePreservedCount() + result.stylePreservedCount(),
                        aggregate.fallbackStyleCount() + result.fallbackStyleCount()
                );
                stageInput = stageOutput;
            }

            if (aggregate.matchesReplaced() == 0) {
                throw new IllegalArgumentException("No matching text was found in the PDF.");
            }

            String outputName = outputName(pdf.getOriginalFilename());
            return new ReplacementOutput(outputName, Files.readAllBytes(stageInput), aggregate);
        } finally {
            deleteIfExists(fontPath);
            deleteIfExists(stageOutput);
            deleteIfExists(input);
            deleteIfExists(workDir);
        }
    }

    private void validateBatch(
            MultipartFile[] pdfFiles,
            List<String> searchList,
            List<String> replacementList,
            String replaceScopeRaw,
            Integer occurrenceIndex
    ) {
        if (pdfFiles == null || pdfFiles.length == 0) {
            throw new IllegalArgumentException("Upload at least one PDF file.");
        }
        if (pdfFiles.length > maxFiles) {
            throw new IllegalArgumentException("Too many files uploaded. Maximum allowed is " + maxFiles + ".");
        }
        long totalBytes = 0;
        for (MultipartFile pdf : pdfFiles) {
            if (pdf == null || pdf.isEmpty()) {
                throw new IllegalArgumentException("Upload a valid PDF file.");
            }
            if (pdf.getSize() > maxFileSizeBytes) {
                throw new IllegalArgumentException("File '" + safeFilename(pdf.getOriginalFilename())
                        + "' exceeds per-file limit of " + maxFileSizeBytes + " bytes.");
            }
            totalBytes += pdf.getSize();
            String filename = pdf.getOriginalFilename();
            if (filename != null && !filename.toLowerCase().endsWith(".pdf")) {
                throw new IllegalArgumentException("Only PDF files are supported.");
            }
        }
        if (totalBytes > maxTotalUploadBytes) {
            throw new IllegalArgumentException("Total upload size exceeds the limit of " + maxTotalUploadBytes + " bytes.");
        }
        if (searchList == null || searchList.isEmpty()) {
            throw new IllegalArgumentException("At least one search text is required.");
        }
        if (!replacementList.isEmpty() && replacementList.size() != searchList.size()) {
            throw new IllegalArgumentException("Replacement values must match the number of search values.");
        }
        for (String search : searchList) {
            if (search == null || search.isBlank()) {
                throw new IllegalArgumentException("Search text is required.");
            }
            if (search.length() > maxSearchLength) {
                throw new IllegalArgumentException("Search text exceeds max length of " + maxSearchLength + " characters.");
            }
        }
        for (String replacement : replacementList) {
            if (replacement != null && replacement.length() > maxReplacementLength) {
                throw new IllegalArgumentException("Replacement text exceeds max length of " + maxReplacementLength + " characters.");
            }
        }
        if ("nth".equalsIgnoreCase(replaceScopeRaw) && (occurrenceIndex == null || occurrenceIndex < 1)) {
            throw new IllegalArgumentException("Occurrence index must be provided as a positive number for nth scope.");
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

    private void enforcePageLimit(Path input) throws IOException {
        try (PDDocument document = PDDocument.load(input.toFile())) {
            int pages = document.getNumberOfPages();
            if (pages > maxPages) {
                throw new IllegalArgumentException("PDF has " + pages + " pages, exceeding the limit of " + maxPages + ".");
            }
        }
    }

    private static DeterministicPdfReplacer.MatchMode parseMatchMode(String value) {
        if (value == null || value.isBlank()) {
            return DeterministicPdfReplacer.MatchMode.EXACT;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "exact" -> DeterministicPdfReplacer.MatchMode.EXACT;
            case "caseinsensitive" -> DeterministicPdfReplacer.MatchMode.CASE_INSENSITIVE;
            case "wholeword" -> DeterministicPdfReplacer.MatchMode.WHOLE_WORD;
            case "caseinsensitivewholeword" -> DeterministicPdfReplacer.MatchMode.CASE_INSENSITIVE_WHOLE_WORD;
            default -> throw new IllegalArgumentException("Unsupported match mode: " + value);
        };
    }

    private static DeterministicPdfReplacer.ReplaceScope parseReplaceScope(String value) {
        if (value == null || value.isBlank()) {
            return DeterministicPdfReplacer.ReplaceScope.ALL;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "all" -> DeterministicPdfReplacer.ReplaceScope.ALL;
            case "first" -> DeterministicPdfReplacer.ReplaceScope.FIRST;
            case "nth" -> DeterministicPdfReplacer.ReplaceScope.NTH;
            default -> throw new IllegalArgumentException("Unsupported replace scope: " + value);
        };
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

    private static String safeFilename(String name) {
        if (name == null || name.isBlank()) {
            return "document.pdf";
        }
        String base = Path.of(name).getFileName().toString().replaceAll("[\\r\\n\\\\/]+", "_");
        return base.isBlank() ? "document.pdf" : base;
    }

    private static String uniqueZipName(String name, Set<String> usedNames) {
        String candidate = name;
        int index = 1;
        while (usedNames.contains(candidate)) {
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                candidate = name.substring(0, dot) + "-" + index + name.substring(dot);
            } else {
                candidate = name + "-" + index;
            }
            index++;
        }
        usedNames.add(candidate);
        return candidate;
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

    public record BatchReplacementOutput(String filename, byte[] bytes, String contentType, DeterministicPdfReplacer.Result summary) {
    }

    private record ReplacementRule(String search, String replacement) {
    }

    private static List<ReplacementRule> buildRules(List<String> searchList, List<String> replacementList) {
        List<ReplacementRule> rules = new ArrayList<>();
        for (int i = 0; i < searchList.size(); i++) {
            String replacement = i < replacementList.size() ? replacementList.get(i) : "";
            rules.add(new ReplacementRule(searchList.get(i), replacement == null ? "" : replacement));
        }
        return rules;
    }
}
