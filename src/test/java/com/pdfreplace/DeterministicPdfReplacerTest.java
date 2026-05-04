package com.pdfreplace;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicPdfReplacerTest {
    @TempDir
    Path tempDir;

    @Test
    void replacesOnlyFirstWhenScopeIsFirst() throws Exception {
        File input = PdfTestSupport.createPdfWithText(tempDir.resolve("first-input.pdf"), "Invoice Invoice");
        File output = tempDir.resolve("first-output.pdf").toFile();

        DeterministicPdfReplacer.Result result = DeterministicPdfReplacer.replace(
                input,
                output,
                "Invoice",
                "Bill",
                false,
                null,
                DeterministicPdfReplacer.MatchMode.EXACT,
                DeterministicPdfReplacer.ReplaceScope.FIRST,
                null
        );

        assertEquals(2, result.matchesFound());
        assertEquals(1, result.matchesReplaced());
        assertTrue(extractText(output).contains("Bill Invoice"));
    }

    @Test
    void respectsWholeWordMatching() throws Exception {
        File input = PdfTestSupport.createPdfWithText(tempDir.resolve("whole-word-input.pdf"), "cat scatter cat");
        File output = tempDir.resolve("whole-word-output.pdf").toFile();

        DeterministicPdfReplacer.Result result = DeterministicPdfReplacer.replace(
                input,
                output,
                "cat",
                "dog",
                false,
                null,
                DeterministicPdfReplacer.MatchMode.WHOLE_WORD,
                DeterministicPdfReplacer.ReplaceScope.ALL,
                null
        );

        assertEquals(2, result.matchesReplaced());
        String outputText = extractText(output);
        assertTrue(outputText.contains("dog scatter dog"));
    }

    @Test
    void replacesRequestedNthOccurrence() throws Exception {
        File input = PdfTestSupport.createPdfWithText(tempDir.resolve("nth-input.pdf"), "alpha alpha alpha");
        File output = tempDir.resolve("nth-output.pdf").toFile();

        DeterministicPdfReplacer.Result result = DeterministicPdfReplacer.replace(
                input,
                output,
                "alpha",
                "beta",
                false,
                null,
                DeterministicPdfReplacer.MatchMode.EXACT,
                DeterministicPdfReplacer.ReplaceScope.NTH,
                2
        );

        assertEquals(3, result.matchesFound());
        assertEquals(1, result.matchesReplaced());
        assertTrue(extractText(output).contains("alpha beta alpha"));
    }

    @Test
    void preservesItalicStyleWhenTextIsEncodable() throws Exception {
        File input = PdfTestSupport.createPdfWithFont(tempDir.resolve("italic-input.pdf"), "sarvesh", PDType1Font.HELVETICA_OBLIQUE);
        File output = tempDir.resolve("italic-output.pdf").toFile();

        DeterministicPdfReplacer.Result result = DeterministicPdfReplacer.replace(
                input,
                output,
                "sarvesh",
                "jungbahadur",
                false,
                null,
                DeterministicPdfReplacer.MatchMode.EXACT,
                DeterministicPdfReplacer.ReplaceScope.ALL,
                null,
                true
        );

        assertEquals(1, result.matchesReplaced());
        assertTrue(result.stylePreservedCount() > 0);
        assertEquals(0, result.fallbackStyleCount());
    }

    @Test
    void fallsBackToClosestFontWhenExactStyleUnavailable() throws Exception {
        File input = PdfTestSupport.createPdfWithFont(tempDir.resolve("bold-italic-input.pdf"), "A", PDType1Font.HELVETICA_BOLD_OBLIQUE);
        File output = tempDir.resolve("bold-italic-output.pdf").toFile();
        File fallbackFont = new File("/System/Library/Fonts/Supplemental/Arial.ttf");
        Assumptions.assumeTrue(fallbackFont.isFile(), "System fallback font not available");

        DeterministicPdfReplacer.Result result = DeterministicPdfReplacer.replace(
                input,
                output,
                "A",
                "\uD83D\uDE42",
                false,
                fallbackFont,
                DeterministicPdfReplacer.MatchMode.EXACT,
                DeterministicPdfReplacer.ReplaceScope.ALL,
                null,
                true
        );
        assertEquals(1, result.matchesReplaced());
        assertTrue(result.fallbackStyleCount() >= 1);
    }

    private static String extractText(File file) throws Exception {
        try (PDDocument document = PDDocument.load(file)) {
            return new PDFTextStripper().getText(document).trim().replace('\n', ' ');
        }
    }
}
