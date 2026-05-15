package com.pdfreplace;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;


import static org.junit.jupiter.api.Assertions.*;

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

    @Disabled("Emoji replacement requires an installed font whose cmap encodes those glyphs via PDFBox; CI/dev machines vary widely.")
    @Test
    void fallsBackToClosestFontWhenExactStyleUnavailable() throws Exception {
        File input = PdfTestSupport.createPdfWithFont(tempDir.resolve("bold-italic-input.pdf"), "A", PDType1Font.HELVETICA_BOLD_OBLIQUE);
        File output = tempDir.resolve("bold-italic-output.pdf").toFile();

        DeterministicPdfReplacer.Result result = DeterministicPdfReplacer.replace(
                input,
                output,
                "A",
                "\uD83D\uDE42",
                false,
                null,
                DeterministicPdfReplacer.MatchMode.EXACT,
                DeterministicPdfReplacer.ReplaceScope.ALL,
                null,
                true
        );
        assertEquals(1, result.matchesReplaced());
        assertTrue(result.fallbackStyleCount() >= 1);
    }

    @Test
    void substituteSimilarityIsZeroWithoutReferenceFont() {
        assertEquals(0.0, DeterministicPdfReplacer.substituteSimilarityScore(null, Path.of("LiberationSans-Bold.ttf")), 1e-9);
    }

    @Test
    void substituteSimilarityScoresBoldHigherThanRegularForBoldReference() {
        double boldFile = DeterministicPdfReplacer.substituteSimilarityScore(PDType1Font.HELVETICA_BOLD, Path.of("DejaVuSans-Bold.ttf"));
        double regularFile = DeterministicPdfReplacer.substituteSimilarityScore(PDType1Font.HELVETICA_BOLD, Path.of("DejaVuSans.ttf"));
        assertTrue(boldFile > regularFile, () -> "boldScore=" + boldFile + " regularScore=" + regularFile);
    }

    private static Float twRestoreOperandAfterShowText(File pdfFile) throws Exception {
        List<Object> tokens = tokensFirstPage(pdfFile);
        for (int i = 0; i + 3 < tokens.size(); i++) {
            if (!(tokens.get(i) instanceof COSString)) {
                continue;
            }
            if (!(tokens.get(i + 1) instanceof Operator op) || !"Tj".equals(op.getName())) {
                continue;
            }
            if (!(tokens.get(i + 2) instanceof COSNumber n)) {
                return null;
            }
            if (!(tokens.get(i + 3) instanceof Operator tw) || !"Tw".equals(tw.getName())) {
                return null;
            }
            return n.floatValue();
        }
        return null;
    }

    private static boolean hasTwFenceBeforeAfterTj(File pdfFile) throws Exception {
        List<Object> tokens = tokensFirstPage(pdfFile);
        for (int i = 2; i + 3 < tokens.size(); i++) {
            if (!(tokens.get(i) instanceof COSString)) {
                continue;
            }
            if (!(tokens.get(i + 1) instanceof Operator op) || !"Tj".equals(op.getName())) {
                continue;
            }
            boolean pre =
                    tokens.get(i - 1) instanceof Operator preTw && "Tw".equals(preTw.getName()) && tokens.get(i - 2) instanceof COSNumber;
            boolean post =
                    tokens.get(i + 2) instanceof COSNumber && tokens.get(i + 3) instanceof Operator postTw && "Tw".equals(postTw.getName());
            return pre && post;
        }
        return false;
    }

    private static List<Object> tokensFirstPage(File pdfFile) throws IOException {
        try (PDDocument document = PDDocument.load(pdfFile)) {
            PDFStreamParser parser = new PDFStreamParser(document.getPage(0));
            parser.parse();
            return parser.getTokens();
        }
    }

    @Test
    void skipsTwFenceWhenReplacementHasNoAsciiSpaces() throws Exception {
        File input = PdfTestSupport.createPdfWithText(tempDir.resolve("tw-plain.pdf"), "HelloWorld");
        File output = tempDir.resolve("tw-plain-out.pdf").toFile();

        DeterministicPdfReplacer.replace(
                input,
                output,
                "HelloWorld",
                "HelloEarth",
                false,
                null,
                DeterministicPdfReplacer.MatchMode.EXACT,
                DeterministicPdfReplacer.ReplaceScope.ALL,
                null
        );

        assertFalse(hasTwFenceBeforeAfterTj(output));
    }

    @Test
    void appliesTwFenceWhenIntroducingSpaces() throws Exception {
        File input = PdfTestSupport.createPdfWithText(tempDir.resolve("tw-one-word.pdf"), "Planet");
        File output = tempDir.resolve("tw-one-word-out.pdf").toFile();

        DeterministicPdfReplacer.replace(
                input,
                output,
                "Planet",
                "Two Worlds",
                false,
                null,
                DeterministicPdfReplacer.MatchMode.EXACT,
                DeterministicPdfReplacer.ReplaceScope.ALL,
                null
        );

        assertTrue(hasTwFenceBeforeAfterTj(output));
        assertTrue(extractText(output).contains("Two Worlds"));
    }

    @Test
    void appliesTwFenceWhenSpacedReplacementChangesWidth() throws Exception {
        File input = PdfTestSupport.createPdfWithText(tempDir.resolve("tw-phrase.pdf"), "Two Words");
        File output = tempDir.resolve("tw-phrase-out.pdf").toFile();

        DeterministicPdfReplacer.replace(
                input,
                output,
                "Two Words",
                "One Gigantic",
                false,
                null,
                DeterministicPdfReplacer.MatchMode.EXACT,
                DeterministicPdfReplacer.ReplaceScope.ALL,
                null
        );

        assertTrue(hasTwFenceBeforeAfterTj(output));
    }

    @Test
    void restoresPriorWordSpacingAfterTransientTw() throws Exception {
        float priorTw = 5f;
        File input = PdfTestSupport.createPdfWithWordSpacing(tempDir.resolve("tw-prior.pdf"), "Hi", priorTw);
        File output = tempDir.resolve("tw-prior-out.pdf").toFile();

        DeterministicPdfReplacer.replace(
                input,
                output,
                "Hi",
                "H i",
                false,
                null,
                DeterministicPdfReplacer.MatchMode.EXACT,
                DeterministicPdfReplacer.ReplaceScope.ALL,
                null
        );

        assertTrue(hasTwFenceBeforeAfterTj(output));
        Float restored = twRestoreOperandAfterShowText(output);
        assertNotNull(restored);
        assertEquals(priorTw, restored, 0.001f);
    }

    @Test
    void skipsTwWhenReplacementUsesNbspInsteadOfAsciiSpace() throws Exception {
        File input = PdfTestSupport.createPdfWithText(tempDir.resolve("tw-nbsp.pdf"), "Z");
        File output = tempDir.resolve("tw-nbsp-out.pdf").toFile();

        DeterministicPdfReplacer.replace(
                input,
                output,
                "Z",
                "a\u00A0c",
                false,
                null,
                DeterministicPdfReplacer.MatchMode.EXACT,
                DeterministicPdfReplacer.ReplaceScope.ALL,
                null
        );

        assertFalse(hasTwFenceBeforeAfterTj(output));
    }

    private static String extractText(File file) throws Exception {
        try (PDDocument document = PDDocument.load(file)) {
            return new PDFTextStripper().getText(document).trim().replace('\n', ' ');
        }
    }
}
