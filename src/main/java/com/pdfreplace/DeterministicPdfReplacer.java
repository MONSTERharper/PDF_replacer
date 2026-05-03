package com.pdfreplace;

import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.PDResources;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class DeterministicPdfReplacer {
    private DeterministicPdfReplacer() {
    }

    public record Result(int pagesScanned, int matchesReplaced, int segmentsChanged) {
    }

    public static Result replace(
            File input,
            File output,
            String search,
            String replacement,
            boolean strictSameLength
    ) throws IOException {
        return replace(input, output, search, replacement, strictSameLength, null);
    }

    public static Result replace(
            File input,
            File output,
            String search,
            String replacement,
            boolean strictSameLength,
            File substituteFontFile
    ) throws IOException {
        if (search == null || search.isEmpty()) {
            throw new IllegalArgumentException("search text must not be empty");
        }
        if (strictSameLength && search.length() != replacement.length()) {
            throw new IllegalArgumentException("--strict requires search and replacement to have the same character length");
        }

        int pagesScanned = 0;
        int matchesReplaced = 0;
        int segmentsChanged = 0;

        try (PDDocument document = PDDocument.load(input)) {
            PDType0Font substituteFont = loadSubstituteFont(document, substituteFontFile, replacement);
            COSName substituteFontName = COSName.getPDFName("FSubPdfReplace");

            for (PDPage page : document.getPages()) {
                pagesScanned++;

                PDFStreamParser parser = new PDFStreamParser(page);
                parser.parse();
                List<Object> tokens = parser.getTokens();

                List<TextSegment> segments = collectTextSegments(page, tokens);
                if (segments.isEmpty()) {
                    continue;
                }

                StringBuilder pageText = new StringBuilder();
                for (TextSegment segment : segments) {
                    segment.start = pageText.length();
                    pageText.append(segment.text);
                    segment.end = pageText.length();
                }

                List<Match> matches = findMatches(pageText.toString(), search);
                if (matches.isEmpty()) {
                    continue;
                }

                for (Match match : matches) {
                    int changed = applyMatch(tokens, segments, match.start(), match.end(), replacement, strictSameLength, substituteFont);
                    if (changed > 0) {
                        matchesReplaced++;
                        segmentsChanged += changed;
                    }
                }

                applyDynamicReplacementDraws(page, tokens, segments, substituteFontName, substituteFont);
                if (substituteFont != null) {
                    applySubstituteFontSwitches(page, tokens, segments, substituteFontName, substituteFont);
                }

                PDStream updatedStream = new PDStream(document);
                try (OutputStream stream = updatedStream.createOutputStream()) {
                    ContentStreamWriter writer = new ContentStreamWriter(stream);
                    writer.writeTokens(tokens);
                }
                page.setContents(updatedStream);
            }

            document.save(output);
        }

        return new Result(pagesScanned, matchesReplaced, segmentsChanged);
    }

    public static void debugSegments(File input, String search, int contextSegments, boolean printTokens) throws IOException {
        try (PDDocument document = PDDocument.load(input)) {
            int pageNumber = 0;
            for (PDPage page : document.getPages()) {
                pageNumber++;
                PDFStreamParser parser = new PDFStreamParser(page);
                parser.parse();
                List<Object> tokens = parser.getTokens();
                List<TextSegment> segments = collectTextSegments(page, tokens);

                StringBuilder pageText = new StringBuilder();
                for (TextSegment segment : segments) {
                    segment.start = pageText.length();
                    pageText.append(segment.text);
                    segment.end = pageText.length();
                }

                List<Match> matches = findMatches(pageText.toString(), search);
                for (Match match : matches) {
                    System.out.println("Page " + pageNumber + " match " + match.start() + "-" + match.end());
                    int first = 0;
                    int last = segments.size() - 1;
                    for (int i = 0; i < segments.size(); i++) {
                        TextSegment segment = segments.get(i);
                        if (segment.end > match.start() && segment.start < match.end()) {
                            first = Math.max(0, i - contextSegments);
                            last = Math.min(segments.size() - 1, i + contextSegments);
                            break;
                        }
                    }

                    for (int i = first; i <= last; i++) {
                        TextSegment segment = segments.get(i);
                        boolean hit = segment.end > match.start() && segment.start < match.end();
                        String parent = segment.parentArray == null
                                ? "none"
                                : Integer.toHexString(System.identityHashCode(segment.parentArray));
                        System.out.printf(
                                "%s idx=%d range=%d-%d op=%d array=%s arrayIndex=%d text=[%s]%n",
                                hit ? "*" : " ",
                                i,
                                segment.start,
                                segment.end,
                                segment.operatorIndex,
                                parent,
                                segment.arrayIndex,
                                segment.text.replace("\n", "\\n")
                        );
                        if (printTokens && hit) {
                            int fromToken = Math.max(0, segment.operatorIndex - 8);
                            int toToken = Math.min(tokens.size() - 1, segment.operatorIndex + 2);
                            for (int tokenIndex = fromToken; tokenIndex <= toToken; tokenIndex++) {
                                System.out.println("    token " + tokenIndex + ": " + tokens.get(tokenIndex));
                            }
                        }
                    }
                }
            }
        }
    }

    private static List<TextSegment> collectTextSegments(PDPage page, List<Object> tokens) throws IOException {
        List<TextSegment> segments = new ArrayList<>();
        PDFont currentFont = null;
        COSName currentFontResourceName = null;
        COSBase currentFontSize = null;
        PDResources resources = page.getResources();

        for (int i = 0; i < tokens.size(); i++) {
            Object token = tokens.get(i);
            if (!(token instanceof Operator operator)) {
                continue;
            }

            String op = operator.getName();
            if ("Tf".equals(op)) {
                FontState fontState = resolveFont(resources, tokens, i);
                currentFont = fontState.font();
                currentFontResourceName = fontState.fontResourceName();
                currentFontSize = fontState.fontSize();
            } else if ("Tj".equals(op) || "'".equals(op) || "\"".equals(op)) {
                Object textToken = previousTextOperand(tokens, i);
                if (textToken instanceof COSString cosString && currentFont != null) {
                    segments.add(new TextSegment(cosString, currentFont, decode(currentFont, cosString), null, -1, i, currentFontResourceName, currentFontSize));
                }
            } else if ("TJ".equals(op)) {
                Object textArray = previousTextOperand(tokens, i);
                if (textArray instanceof COSArray array && currentFont != null) {
                    for (int itemIndex = 0; itemIndex < array.size(); itemIndex++) {
                        COSBase item = array.get(itemIndex);
                        if (item instanceof COSString cosString) {
                            segments.add(new TextSegment(cosString, currentFont, decode(currentFont, cosString), array, itemIndex, i, currentFontResourceName, currentFontSize));
                        }
                    }
                }
            }
        }

        return segments;
    }

    private static FontState resolveFont(PDResources resources, List<Object> tokens, int operatorIndex) throws IOException {
        if (resources == null || operatorIndex < 2) {
            return new FontState(null, null, null);
        }

        Object fontNameToken = tokens.get(operatorIndex - 2);
        Object fontSizeToken = tokens.get(operatorIndex - 1);
        if (fontNameToken instanceof COSName fontName && fontSizeToken instanceof COSNumber fontSize) {
            return new FontState(resources.getFont(fontName), fontName, fontSize);
        }
        return new FontState(null, null, null);
    }

    private static Object previousTextOperand(List<Object> tokens, int operatorIndex) {
        if (operatorIndex == 0) {
            return null;
        }
        return tokens.get(operatorIndex - 1);
    }

    private static String decode(PDFont font, COSString value) throws IOException {
        byte[] bytes = value.getBytes();
        StringBuilder text = new StringBuilder();

        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            while (input.available() > 0) {
                int code = font.readCode(input);
                String unicode = font.toUnicode(code);
                if (unicode != null) {
                    text.append(unicode);
                }
            }
        }

        if (text.length() == 0) {
            return value.getString();
        }
        return text.toString();
    }

    private static List<Match> findMatches(String text, String search) {
        List<Match> matches = new ArrayList<>();
        int offset = 0;
        while (offset >= 0) {
            offset = text.indexOf(search, offset);
            if (offset >= 0) {
                matches.add(new Match(offset, offset + search.length()));
                offset += search.length();
            }
        }
        matches.sort(Comparator.comparingInt(Match::start).reversed());
        return matches;
    }

    private static int applyMatch(
            List<Object> tokens,
            List<TextSegment> segments,
            int matchStart,
            int matchEnd,
            String replacement,
            boolean strictSameLength,
            PDFont substituteFont
    ) throws IOException {
        List<TextSegment> affected = segments.stream()
                .filter(segment -> segment.end > matchStart && segment.start < matchEnd)
                .toList();

        if (affected.isEmpty()) {
            return 0;
        }

        if (strictSameLength) {
            return applyStrictSameLength(affected, matchStart, matchEnd, replacement, substituteFont);
        }

        return applyFlexible(tokens, segments, affected, matchStart, matchEnd, replacement, substituteFont);
    }

    private static int applyStrictSameLength(
            List<TextSegment> affected,
            int matchStart,
            int matchEnd,
            String replacement,
            PDFont substituteFont
    ) throws IOException {
        int replacementOffset = 0;
        int changed = 0;

        for (TextSegment segment : affected) {
            int localStart = Math.max(0, matchStart - segment.start);
            int localEnd = Math.min(segment.text.length(), matchEnd - segment.start);
            int localLength = localEnd - localStart;

            String patch = replacement.substring(replacementOffset, replacementOffset + localLength);
            String newText = segment.text.substring(0, localStart) + patch + segment.text.substring(localEnd);
            rewriteSegment(segment, newText, substituteFont);

            replacementOffset += localLength;
            changed++;
        }

        return changed;
    }

    private static int applyFlexible(
            List<Object> tokens,
            List<TextSegment> allSegments,
            List<TextSegment> affected,
            int matchStart,
            int matchEnd,
            String replacement,
            PDFont substituteFont
    ) throws IOException {
        if (canUseDynamicArrayReplacement(affected, matchStart, matchEnd)) {
            return applyDynamicArrayReplacement(affected, matchStart, matchEnd, replacement, substituteFont);
        }

        TextSegment first = affected.get(0);
        int firstLocalStart = Math.max(0, matchStart - first.start);
        int firstLocalEnd = Math.min(first.text.length(), matchEnd - first.start);
        String firstText = first.text.substring(0, firstLocalStart)
                + replacement
                + first.text.substring(firstLocalEnd);
        rewriteSegment(first, firstText, substituteFont);

        int changed = 1;
        for (int i = 1; i < affected.size(); i++) {
            TextSegment segment = affected.get(i);
            int localStart = Math.max(0, matchStart - segment.start);
            int localEnd = Math.min(segment.text.length(), matchEnd - segment.start);
            String newText = segment.text.substring(0, localStart) + segment.text.substring(localEnd);
            rewriteSegment(segment, newText, substituteFont);
            changed++;
        }

        zeroInternalArraySpacing(affected);
        StringBuilder originalMatchedText = new StringBuilder();
        for (TextSegment segment : affected) {
            originalMatchedText.append(segment.originalText);
        }
        if (replacement.length() != originalMatchedText.length()) {
            shiftFollowingPositionedText(tokens, allSegments, affected, replacement, first.usesSubstituteFont ? substituteFont : first.font);
        }
        return changed;
    }

    private static void shiftFollowingPositionedText(
            List<Object> tokens,
            List<TextSegment> allSegments,
            List<TextSegment> affected,
            String replacement,
            PDFont replacementFont
    ) throws IOException {
        if (affected.isEmpty() || affected.get(0).parentArray != null) {
            return;
        }

        TextSegment first = affected.get(0);
        TextSegment last = affected.get(affected.size() - 1);
        int lastIndex = allSegments.indexOf(last);
        if (lastIndex < 0 || lastIndex + 1 >= allSegments.size()) {
            return;
        }

        TextSegment next = allSegments.get(lastIndex + 1);
        float delta = estimateReplacementDelta(tokens, affected, replacement, replacementFont);
        if (Math.abs(delta) < 0.001f) {
            return;
        }

        if (!shiftPositionBeforeSegment(tokens, next, delta)) {
            return;
        }

        first.dynamicShiftApplied = delta;
    }

    private static float estimateReplacementDelta(
            List<Object> tokens,
            List<TextSegment> affected,
            String replacement,
            PDFont replacementFont
    ) throws IOException {
        TextSegment first = affected.get(0);
        float originalWidth = 0;
        for (int i = 1; i < affected.size(); i++) {
            originalWidth += precedingTdX(tokens, affected.get(i));
        }

        int afterLastOperator = affected.get(affected.size() - 1).operatorIndex;
        if (afterLastOperator + 2 < tokens.size()
                && tokens.get(afterLastOperator + 2) instanceof Operator operator
                && "Td".equals(operator.getName())
                && tokens.get(afterLastOperator + 1) instanceof COSNumber dy
                && Math.abs(dy.floatValue()) < 0.001f
                && tokens.get(afterLastOperator + 1 - 1) instanceof COSNumber dx) {
            originalWidth += dx.floatValue();
        }

        if (originalWidth <= 0) {
            return 0;
        }

        StringBuilder originalText = new StringBuilder();
        for (TextSegment segment : affected) {
            originalText.append(segment.originalText);
        }

        float originalFontWidth = Math.max(1, first.font.getStringWidth(originalText.toString()));
        float scale = originalWidth / originalFontWidth;
        try {
            float replacementWidth = replacementFont.getStringWidth(replacement) * scale;
            return replacementWidth - originalWidth;
        } catch (IllegalArgumentException e) {
            float averageOriginalCharWidth = originalWidth / Math.max(1, originalText.length());
            float replacementWidth = averageOriginalCharWidth * replacement.length();
            return replacementWidth - originalWidth;
        }
    }

    private static float precedingTdX(List<Object> tokens, TextSegment segment) {
        int tdOperatorIndex = segment.operatorIndex - 2;
        if (tdOperatorIndex >= 2
                && tokens.get(tdOperatorIndex) instanceof Operator operator
                && "Td".equals(operator.getName())
                && tokens.get(tdOperatorIndex - 2) instanceof COSNumber dx
                && tokens.get(tdOperatorIndex - 1) instanceof COSNumber dy
                && Math.abs(dy.floatValue()) < 0.001f) {
            return dx.floatValue();
        }
        return 0;
    }

    private static boolean shiftPositionBeforeSegment(List<Object> tokens, TextSegment segment, float delta) {
        int tdOperatorIndex = segment.operatorIndex - 2;
        if (tdOperatorIndex >= 2
                && tokens.get(tdOperatorIndex) instanceof Operator operator
                && "Td".equals(operator.getName())
                && tokens.get(tdOperatorIndex - 2) instanceof COSNumber dx
                && tokens.get(tdOperatorIndex - 1) instanceof COSNumber dy
                && Math.abs(dy.floatValue()) < 0.001f) {
            tokens.set(tdOperatorIndex - 2, new org.apache.pdfbox.cos.COSFloat(dx.floatValue() + delta));
            return true;
        }

        int tmOperatorIndex = segment.operatorIndex - 2;
        if (tmOperatorIndex >= 6
                && tokens.get(tmOperatorIndex) instanceof Operator operator
                && "Tm".equals(operator.getName())
                && tokens.get(tmOperatorIndex - 2) instanceof COSNumber x) {
            tokens.set(tmOperatorIndex - 2, new org.apache.pdfbox.cos.COSFloat(x.floatValue() + delta));
            return true;
        }

        return false;
    }

    private static boolean canUseDynamicArrayReplacement(List<TextSegment> affected, int matchStart, int matchEnd) {
        if (affected.isEmpty()) {
            return false;
        }

        TextSegment first = affected.get(0);
        TextSegment last = affected.get(affected.size() - 1);
        if (first.parentArray == null || matchStart != first.start || matchEnd != last.end) {
            return false;
        }

        for (TextSegment segment : affected) {
            if (segment.parentArray != first.parentArray || segment.operatorIndex != first.operatorIndex) {
                return false;
            }
        }
        return true;
    }

    private static int applyDynamicArrayReplacement(
            List<TextSegment> affected,
            int matchStart,
            int matchEnd,
            String replacement,
            PDFont substituteFont
    ) throws IOException {
        TextSegment first = affected.get(0);
        PDFont replacementFont = chooseEncodingFont(first.font, replacement, substituteFont);
        first.dynamicReplacementText = replacement;
        first.dynamicReplacementFont = replacementFont;
        first.dynamicUsesSubstituteFont = replacementFont == substituteFont;

        int changed = 0;
        for (TextSegment segment : affected) {
            int localStart = Math.max(0, matchStart - segment.start);
            int localEnd = Math.min(segment.text.length(), matchEnd - segment.start);
            String newText = segment.text.substring(0, localStart) + segment.text.substring(localEnd);
            rewriteSegment(segment, newText, null);
            changed++;
        }

        zeroInternalArraySpacing(affected);
        return changed;
    }

    private static PDFont chooseEncodingFont(PDFont originalFont, String text, PDFont substituteFont) throws IOException {
        try {
            originalFont.encode(text);
            return originalFont;
        } catch (IllegalArgumentException e) {
            if (substituteFont == null) {
                throw new IOException("Replacement text cannot be encoded with the original embedded font and no substitute font is available.", e);
            }
            substituteFont.encode(text);
            return substituteFont;
        }
    }

    private static void zeroInternalArraySpacing(List<TextSegment> affected) {
        if (affected.size() < 2) {
            return;
        }

        for (int i = 0; i < affected.size() - 1; i++) {
            TextSegment left = affected.get(i);
            TextSegment right = affected.get(i + 1);
            if (left.parentArray == null || left.parentArray != right.parentArray) {
                continue;
            }

            int from = Math.min(left.arrayIndex, right.arrayIndex) + 1;
            int to = Math.max(left.arrayIndex, right.arrayIndex);
            for (int itemIndex = from; itemIndex < to; itemIndex++) {
                if (left.parentArray.get(itemIndex) instanceof COSNumber) {
                    left.parentArray.set(itemIndex, COSInteger.ZERO);
                }
            }
        }
    }

    private static void rewriteSegment(TextSegment segment, String newText, PDFont substituteFont) throws IOException {
        if (segment.font instanceof PDType3Font) {
            throw new IOException("Type3 fonts are not supported by this baseline replacer");
        }
        try {
            segment.cosString.setValue(segment.font.encode(newText));
            segment.usesSubstituteFont = false;
        } catch (IllegalArgumentException e) {
            if (substituteFont == null) {
                throw new IOException("Replacement text cannot be encoded with the original embedded font. "
                        + "The PDF likely uses a subset font that does not contain one or more replacement glyphs. "
                        + "Try replacement text using characters already present in the PDF, or pass --font /path/to/font.ttf.", e);
            }
            try {
                segment.cosString.setValue(substituteFont.encode(newText));
                segment.usesSubstituteFont = true;
            } catch (IllegalArgumentException fallbackError) {
                throw new IOException("Replacement text cannot be encoded with either the original embedded font "
                        + "or the available substitute fonts. Add a broader fallback font such as Noto Sans to the server.", fallbackError);
            }
        }
        segment.text = newText;
    }

    private static PDType0Font loadSubstituteFont(
            PDDocument document,
            File requestedFontFile,
            String replacement
    ) throws IOException {
        List<File> candidates = new ArrayList<>();
        if (requestedFontFile != null) {
            candidates.add(requestedFontFile);
        }

        candidates.add(new File("fonts/NotoSans-Regular.ttf"));
        candidates.add(new File("src/main/resources/fonts/NotoSans-Regular.ttf"));
        candidates.add(new File("/System/Library/Fonts/Supplemental/Arial.ttf"));
        candidates.add(new File("/System/Library/Fonts/Supplemental/Arial Unicode.ttf"));
        candidates.add(new File("/Library/Fonts/Arial Unicode.ttf"));
        candidates.add(new File("/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf"));
        candidates.add(new File("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"));

        for (File candidate : candidates) {
            if (!candidate.isFile()) {
                continue;
            }
            try {
                PDType0Font font = PDType0Font.load(document, candidate);
                font.encode(replacement);
                return font;
            } catch (IOException | IllegalArgumentException ignored) {
                // Try the next fallback font.
            }
        }

        return null;
    }

    private static void applyDynamicReplacementDraws(
            PDPage page,
            List<Object> tokens,
            List<TextSegment> segments,
            COSName substituteFontName,
            PDFont substituteFont
    ) throws IOException {
        List<TextSegment> dynamicSegments = segments.stream()
                .filter(segment -> segment.dynamicReplacementText != null)
                .sorted(Comparator.comparingInt((TextSegment segment) -> segment.operatorIndex).reversed())
                .toList();

        if (dynamicSegments.isEmpty()) {
            return;
        }

        PDResources resources = page.getResources();
        if (resources == null) {
            resources = new PDResources();
            page.setResources(resources);
        }
        if (substituteFont != null) {
            resources.put(substituteFontName, substituteFont);
        }

        for (TextSegment segment : dynamicSegments) {
            COSName drawFontName = segment.dynamicUsesSubstituteFont ? substituteFontName : segment.fontResourceName;
            if (drawFontName == null || segment.fontSize == null) {
                continue;
            }

            COSString replacementString = new COSString("");
            replacementString.setValue(segment.dynamicReplacementFont.encode(segment.dynamicReplacementText));

            int insertBefore = Math.max(0, segment.operatorIndex - 1);
            tokens.add(insertBefore, drawFontName);
            tokens.add(insertBefore + 1, segment.fontSize);
            tokens.add(insertBefore + 2, Operator.getOperator("Tf"));
            tokens.add(insertBefore + 3, replacementString);
            tokens.add(insertBefore + 4, Operator.getOperator("Tj"));
            tokens.add(insertBefore + 5, segment.fontResourceName);
            tokens.add(insertBefore + 6, segment.fontSize);
            tokens.add(insertBefore + 7, Operator.getOperator("Tf"));
        }
    }

    private static void applySubstituteFontSwitches(
            PDPage page,
            List<Object> tokens,
            List<TextSegment> segments,
            COSName substituteFontName,
            PDFont substituteFont
    ) {
        PDResources resources = page.getResources();
        if (resources == null) {
            resources = new PDResources();
            page.setResources(resources);
        }
        resources.put(substituteFontName, substituteFont);

        List<TextSegment> substituteSegments = segments.stream()
                .filter(segment -> segment.usesSubstituteFont)
                .sorted(Comparator.comparingInt((TextSegment segment) -> segment.operatorIndex).reversed())
                .toList();

        int lastOperatorIndex = -1;
        for (TextSegment segment : substituteSegments) {
            if (segment.operatorIndex == lastOperatorIndex || segment.fontResourceName == null || segment.fontSize == null) {
                continue;
            }
            lastOperatorIndex = segment.operatorIndex;

            Operator operator = (Operator) tokens.get(segment.operatorIndex);
            int operandCount = "\"".equals(operator.getName()) ? 3 : 1;
            int insertBefore = Math.max(0, segment.operatorIndex - operandCount);
            int insertAfter = segment.operatorIndex + 1;

            tokens.add(insertAfter, Operator.getOperator("Tf"));
            tokens.add(insertAfter, segment.fontSize);
            tokens.add(insertAfter, segment.fontResourceName);

            tokens.add(insertBefore, Operator.getOperator("Tf"));
            tokens.add(insertBefore, segment.fontSize);
            tokens.add(insertBefore, substituteFontName);
        }
    }

    private record Match(int start, int end) {
    }

    private record FontState(PDFont font, COSName fontResourceName, COSBase fontSize) {
    }

    private static final class TextSegment {
        private final COSString cosString;
        private final PDFont font;
        private final COSArray parentArray;
        private final int arrayIndex;
        private final int operatorIndex;
        private final COSName fontResourceName;
        private final COSBase fontSize;
        private final String originalText;
        private String text;
        private int start;
        private int end;
        private boolean usesSubstituteFont;
        private String dynamicReplacementText;
        private PDFont dynamicReplacementFont;
        private boolean dynamicUsesSubstituteFont;
        private float dynamicShiftApplied;

        private TextSegment(
                COSString cosString,
                PDFont font,
                String text,
                COSArray parentArray,
                int arrayIndex,
                int operatorIndex,
                COSName fontResourceName,
                COSBase fontSize
        ) {
            this.cosString = cosString;
            this.font = font;
            this.text = text;
            this.originalText = text;
            this.parentArray = parentArray;
            this.arrayIndex = arrayIndex;
            this.operatorIndex = operatorIndex;
            this.fontResourceName = fontResourceName;
            this.fontSize = fontSize;
        }
    }
}
