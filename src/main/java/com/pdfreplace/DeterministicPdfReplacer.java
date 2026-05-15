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
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.PDResources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class DeterministicPdfReplacer {
    private static final Logger LOGGER = LoggerFactory.getLogger(DeterministicPdfReplacer.class);

    /** Max font files to probe after ranking by similarity (avoids scanning thousands on huge trees). */
    private static final int MAX_FONT_DISCOVERY_PROBE =
            Integer.parseInt(System.getProperty("boltreplacer.fontDiscovery.maxProbe", "400"));

    /** Minimum matched-run width delta (points) before applying {@code Tw} repair. */
    private static final float TW_WIDTH_DIFF_THRESHOLD_PT = 0.5f;

    /** Hard limit on injected {@code Tw} magnitude (glyph-space-ish units PDFBox widths use per 1000 em). */
    private static final float TW_MAX_COMBINED_MAGNITUDE = 800f;

    /** Optional extra roots via -Dboltreplacer.fontRoots=/extra/a:/extra/b */
    private static final String EXTRA_FONT_ROOTS_PROP = "boltreplacer.fontRoots";

    private DeterministicPdfReplacer() {
    }

    public enum MatchMode {
        EXACT,
        CASE_INSENSITIVE,
        WHOLE_WORD,
        CASE_INSENSITIVE_WHOLE_WORD
    }

    public enum ReplaceScope {
        ALL,
        FIRST,
        NTH
    }

    public record Result(
            int pagesScanned,
            int matchesFound,
            int matchesReplaced,
            int segmentsChanged,
            Integer requestedOccurrence,
            int stylePreservedCount,
            int fallbackStyleCount
    ) {
    }

    public static Result replace(
            File input,
            File output,
            String search,
            String replacement,
            boolean strictSameLength
    ) throws IOException {
        return replace(input, output, search, replacement, strictSameLength, null, MatchMode.EXACT, ReplaceScope.ALL, null);
    }

    public static Result replace(
            File input,
            File output,
            String search,
            String replacement,
            boolean strictSameLength,
            File substituteFontFile
    ) throws IOException {
        return replace(input, output, search, replacement, strictSameLength, substituteFontFile, MatchMode.EXACT, ReplaceScope.ALL, null);
    }

    public static Result replace(
            File input,
            File output,
            String search,
            String replacement,
            boolean strictSameLength,
            File substituteFontFile,
            MatchMode matchMode,
            ReplaceScope replaceScope,
            Integer requestedOccurrence
    ) throws IOException {
        return replace(
                input,
                output,
                search,
                replacement,
                strictSameLength,
                substituteFontFile,
                matchMode,
                replaceScope,
                requestedOccurrence,
                true,
                true
        );
    }

    public static Result replace(
            File input,
            File output,
            String search,
            String replacement,
            boolean strictSameLength,
            File substituteFontFile,
            MatchMode matchMode,
            ReplaceScope replaceScope,
            Integer requestedOccurrence,
            boolean preserveStyle
    ) throws IOException {
        return replace(
                input,
                output,
                search,
                replacement,
                strictSameLength,
                substituteFontFile,
                matchMode,
                replaceScope,
                requestedOccurrence,
                preserveStyle,
                true
        );
    }

    public static Result replace(
            File input,
            File output,
            String search,
            String replacement,
            boolean strictSameLength,
            File substituteFontFile,
            MatchMode matchMode,
            ReplaceScope replaceScope,
            Integer requestedOccurrence,
            boolean preserveStyle,
            boolean retainMetadata
    ) throws IOException {
        if (search == null || search.isEmpty()) {
            throw new IllegalArgumentException("search text must not be empty");
        }
        if (strictSameLength && search.length() != replacement.length()) {
            throw new IllegalArgumentException("--strict requires search and replacement to have the same character length");
        }

        int pagesScanned = 0;
        int matchesFound = 0;
        int matchesReplaced = 0;
        int segmentsChanged = 0;
        int stylePreservedCount = 0;
        int fallbackStyleCount = 0;

        try (PDDocument document = PDDocument.load(input)) {
            MetadataSnapshot metadataSnapshot = retainMetadata ? captureMetadata(document) : null;
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

                List<Match> pageMatches = findMatches(pageText.toString(), search, matchMode);
                if (pageMatches.isEmpty()) {
                    continue;
                }
                matchesFound += pageMatches.size();
                List<Match> matches = selectMatchesForScope(pageMatches, replaceScope, requestedOccurrence);
                if (matches.isEmpty()) {
                    continue;
                }

                PDFont pageSubstituteFont = chooseStyleCompatibleSubstituteFont(
                        document,
                        substituteFont,
                        replacement,
                        segments,
                        matches,
                        preserveStyle
                );
                for (Match match : matches) {
                    int changed = applyMatch(tokens, segments, match.start(), match.end(), replacement, strictSameLength, pageSubstituteFont, preserveStyle);
                    if (changed > 0) {
                        matchesReplaced++;
                        segmentsChanged += changed;
                    }
                }

                applyDynamicReplacementDraws(page, tokens, segments, substituteFontName, pageSubstituteFont);
                if (pageSubstituteFont != null) {
                    applySubstituteFontSwitches(page, tokens, segments, substituteFontName, pageSubstituteFont);
                }
                for (TextSegment segment : segments) {
                    if (!segment.changed) {
                        continue;
                    }
                    if (segment.usesSubstituteFont || segment.dynamicUsesSubstituteFont) {
                        fallbackStyleCount++;
                    } else {
                        stylePreservedCount++;
                    }
                }

                PDStream updatedStream = new PDStream(document);
                try (OutputStream stream = updatedStream.createOutputStream()) {
                    ContentStreamWriter writer = new ContentStreamWriter(stream);
                    writer.writeTokens(tokens);
                }
                page.setContents(updatedStream);
            }

            if (metadataSnapshot != null) {
                restoreMetadata(document, metadataSnapshot);
            } else {
                PdfBoltMetadata.applyBranding(document);
            }
            document.save(output);
        }

        return new Result(
                pagesScanned,
                matchesFound,
                matchesReplaced,
                segmentsChanged,
                requestedOccurrence,
                stylePreservedCount,
                fallbackStyleCount
        );
    }

    private static MetadataSnapshot captureMetadata(PDDocument document) throws IOException {
        PDDocumentInformation infoCopy = new PDDocumentInformation();
        PDDocumentInformation info = document.getDocumentInformation();
        if (info != null) {
            infoCopy.setTitle(info.getTitle());
            infoCopy.setAuthor(info.getAuthor());
            infoCopy.setSubject(info.getSubject());
            infoCopy.setKeywords(info.getKeywords());
            infoCopy.setCreator(info.getCreator());
            infoCopy.setProducer(info.getProducer());
            infoCopy.setCreationDate(info.getCreationDate());
            infoCopy.setModificationDate(info.getModificationDate());
            infoCopy.setTrapped(info.getTrapped());
        }
        byte[] xmp = null;
        PDMetadata metadata = document.getDocumentCatalog().getMetadata();
        if (metadata != null) {
            try (InputStream in = metadata.exportXMPMetadata()) {
                xmp = in.readAllBytes();
            }
        }
        return new MetadataSnapshot(infoCopy, xmp);
    }

    private static void restoreMetadata(PDDocument document, MetadataSnapshot snapshot) throws IOException {
        document.setDocumentInformation(snapshot.info());
        if (snapshot.xmp() != null && snapshot.xmp().length > 0) {
            PDMetadata metadata = new PDMetadata(document);
            metadata.importXMPMetadata(snapshot.xmp());
            document.getDocumentCatalog().setMetadata(metadata);
        }
    }

    private record MetadataSnapshot(PDDocumentInformation info, byte[] xmp) {
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

                List<Match> matches = findMatches(pageText.toString(), search, MatchMode.EXACT);
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
        float currentX = 0;
        float currentY = 0;
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
            } else if ("Tm".equals(op)) {
                if (i >= 6 && tokens.get(i - 2) instanceof COSNumber x && tokens.get(i - 1) instanceof COSNumber y) {
                    currentX = x.floatValue();
                    currentY = y.floatValue();
                }
            } else if ("Td".equals(op)) {
                if (i >= 2 && tokens.get(i - 2) instanceof COSNumber dx && tokens.get(i - 1) instanceof COSNumber dy) {
                    currentX += dx.floatValue();
                    currentY += dy.floatValue();
                }
            } else if ("Tj".equals(op) || "'".equals(op) || "\"".equals(op)) {
                Object textToken = previousTextOperand(tokens, i);
                if (textToken instanceof COSString cosString && currentFont != null) {
                    segments.add(new TextSegment(cosString, currentFont, decode(currentFont, cosString), null, -1, i, currentFontResourceName, currentFontSize, currentX, currentY));
                }
            } else if ("TJ".equals(op)) {
                Object textArray = previousTextOperand(tokens, i);
                if (textArray instanceof COSArray array && currentFont != null) {
                    for (int itemIndex = 0; itemIndex < array.size(); itemIndex++) {
                        COSBase item = array.get(itemIndex);
                        if (item instanceof COSString cosString) {
                            segments.add(new TextSegment(cosString, currentFont, decode(currentFont, cosString), array, itemIndex, i, currentFontResourceName, currentFontSize, currentX, currentY));
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

    private static List<Match> findMatches(String text, String search, MatchMode matchMode) {
        List<Match> matches = new ArrayList<>();
        String searchIn = search;
        String textIn = text;
        boolean wholeWord = matchMode == MatchMode.WHOLE_WORD || matchMode == MatchMode.CASE_INSENSITIVE_WHOLE_WORD;
        if (matchMode == MatchMode.CASE_INSENSITIVE || matchMode == MatchMode.CASE_INSENSITIVE_WHOLE_WORD) {
            searchIn = search.toLowerCase(Locale.ROOT);
            textIn = text.toLowerCase(Locale.ROOT);
        }

        int offset = 0;
        while (offset >= 0) {
            offset = textIn.indexOf(searchIn, offset);
            if (offset >= 0) {
                int end = offset + search.length();
                if (!wholeWord || isWholeWordBoundary(text, offset, end)) {
                    matches.add(new Match(offset, end));
                }
                offset += search.length();
            }
        }
        return matches;
    }

    private static boolean isWholeWordBoundary(String text, int start, int end) {
        boolean leftBoundary = start == 0 || !isWordCharacter(text.charAt(start - 1));
        boolean rightBoundary = end == text.length() || !isWordCharacter(text.charAt(end));
        return leftBoundary && rightBoundary;
    }

    private static boolean isWordCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }

    private static List<Match> selectMatchesForScope(List<Match> orderedMatches, ReplaceScope replaceScope, Integer requestedOccurrence) {
        if (orderedMatches.isEmpty()) {
            return orderedMatches;
        }

        List<Match> selected = new ArrayList<>();
        switch (replaceScope) {
            case FIRST -> selected.add(orderedMatches.get(0));
            case NTH -> {
                if (requestedOccurrence != null && requestedOccurrence >= 1 && requestedOccurrence <= orderedMatches.size()) {
                    selected.add(orderedMatches.get(requestedOccurrence - 1));
                }
            }
            case ALL -> selected.addAll(orderedMatches);
        }
        selected.sort(Comparator.comparingInt(Match::start).reversed());
        return selected;
    }

    private static int applyMatch(
            List<Object> tokens,
            List<TextSegment> segments,
            int matchStart,
            int matchEnd,
            String replacement,
            boolean strictSameLength,
            PDFont substituteFont,
            boolean preserveStyle
    ) throws IOException {
        List<TextSegment> affected = segments.stream()
                .filter(segment -> segment.end > matchStart && segment.start < matchEnd)
                .toList();

        if (affected.isEmpty()) {
            return 0;
        }

        if (strictSameLength) {
            return applyStrictSameLength(affected, matchStart, matchEnd, replacement, substituteFont, preserveStyle);
        }

        return applyFlexible(tokens, segments, affected, matchStart, matchEnd, replacement, substituteFont, preserveStyle);
    }

    private static int applyStrictSameLength(
            List<TextSegment> affected,
            int matchStart,
            int matchEnd,
            String replacement,
            PDFont substituteFont,
            boolean preserveStyle
    ) throws IOException {
        int replacementOffset = 0;
        int changed = 0;

        for (TextSegment segment : affected) {
            int localStart = Math.max(0, matchStart - segment.start);
            int localEnd = Math.min(segment.text.length(), matchEnd - segment.start);
            int localLength = localEnd - localStart;

            String patch = replacement.substring(replacementOffset, replacementOffset + localLength);
            String newText = segment.text.substring(0, localStart) + patch + segment.text.substring(localEnd);
            rewriteSegment(segment, newText, substituteFont, preserveStyle);

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
            PDFont substituteFont,
            boolean preserveStyle
    ) throws IOException {
        if (canUseDynamicArrayReplacement(affected, matchStart, matchEnd)) {
            return applyDynamicArrayReplacement(affected, matchStart, matchEnd, replacement, substituteFont, preserveStyle);
        }

        List<TextSegment> rewriteRun = findSameBaselineRewriteRun(allSegments, affected);
        if (rewriteRun.size() > affected.size()) {
            return applyRunReconstruction(rewriteRun, matchStart, matchEnd, replacement, substituteFont, preserveStyle);
        }

        String matchedOriginal = matchedSubstring(affected, matchStart, matchEnd);

        TextSegment first = affected.get(0);
        int firstLocalStart = Math.max(0, matchStart - first.start);
        int firstLocalEnd = Math.min(first.text.length(), matchEnd - first.start);
        String firstText = first.text.substring(0, firstLocalStart)
                + replacement
                + first.text.substring(firstLocalEnd);
        rewriteSegment(first, firstText, substituteFont, preserveStyle);

        int changed = 1;
        for (int i = 1; i < affected.size(); i++) {
            TextSegment segment = affected.get(i);
            int localStart = Math.max(0, matchStart - segment.start);
            int localEnd = Math.min(segment.text.length(), matchEnd - segment.start);
            String newText = segment.text.substring(0, localStart) + segment.text.substring(localEnd);
            rewriteSegment(segment, newText, substituteFont, preserveStyle);
            changed++;
        }

        if (!preserveStyle) {
            zeroInternalArraySpacing(affected);
        }
        TextSegment firstAfter = affected.get(0);
        PDFont drawFont = firstAfter.usesSubstituteFont && substituteFont != null ? substituteFont : firstAfter.font;
        boolean lengthMismatch = replacement.length() != matchedOriginal.length();
        boolean widthMismatch = false;
        if (!lengthMismatch) {
            float fontSize = firstAfter.fontSize instanceof COSNumber size ? size.floatValue() : 12.0f;
            try {
                float wOld = stringWidthUserSpace(matchedOriginal, firstAfter.font, fontSize);
                float wNew = stringWidthUserSpace(replacement, drawFont, fontSize);
                widthMismatch = Math.abs(wNew - wOld) > Math.max(0.5f, fontSize * 0.06f);
            } catch (IOException e) {
                LOGGER.trace("Width compare skipped: {}", e.getMessage());
            }
        }

        if (lengthMismatch || widthMismatch) {
            boolean appliedTw =
                    applyTwAdjustment(tokens, firstAfter, matchedOriginal, replacement, firstAfter.font, drawFont);
            if (!appliedTw && widthMismatch && !lengthMismatch) {
                injectTransientCharacterSpacingTc(tokens, firstAfter, matchedOriginal, replacement, drawFont);
            }
            shiftFollowingPositionedText(tokens, allSegments, affected, replacement, drawFont, matchedOriginal);
        }
        return changed;
    }

    /** Text content in the PDF matching [matchStart, matchEnd) across affected segments (pre-rewrite ordering). */
    private static String matchedSubstring(List<TextSegment> affected, int matchStart, int matchEnd) {
        StringBuilder sb = new StringBuilder();
        for (TextSegment segment : affected) {
            if (matchEnd <= segment.start || matchStart >= segment.end) {
                continue;
            }
            int ls = Math.max(0, matchStart - segment.start);
            int le = Math.min(segment.originalText.length(), matchEnd - segment.start);
            if (ls < le) {
                sb.append(segment.originalText, ls, le);
            }
        }
        return sb.toString();
    }

    private static float stringWidthUserSpace(String text, PDFont font, float fontSize) throws IOException {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
        try {
            return font.getStringWidth(text) / 1000.0f * fontSize;
        } catch (IllegalArgumentException e) {
            return 0.48f * fontSize * text.length();
        }
    }

    /**
     * Injects {@code tc Tc … Tj … 0 Tc} around a standalone {@code Tj} when substitution width differs
     * while character count stays the same (cheap layout repair; skipped for TJ/form arrays).
     */
    private static void injectTransientCharacterSpacingTc(
            List<Object> tokens,
            TextSegment segment,
            String matchedOriginal,
            String replacement,
            PDFont drawFont
    ) throws IOException {
        if (segment.parentArray != null || replacement.length() != matchedOriginal.length()) {
            return;
        }
        int op = segment.operatorIndex;
        if (op < 1 || !(tokens.get(op - 1) instanceof COSString) || !(tokens.get(op) instanceof Operator opTok)) {
            return;
        }
        if (!"Tj".equals(opTok.getName())) {
            return;
        }

        float fontSize = segment.fontSize instanceof COSNumber size ? size.floatValue() : 12f;
        float deltaPt =
                stringWidthUserSpace(replacement, drawFont, fontSize)
                        - stringWidthUserSpace(matchedOriginal, segment.font, fontSize);
        if (Math.abs(deltaPt) < Math.max(0.08f, fontSize * 0.02f)) {
            return;
        }

        /*
         * Tc measured in glyph space (typical widths from getStringWidth are 1/1000 em units);
         * map user-space deviation back into proportional Tc.
         */
        int units = Math.max(1, replacement.codePointCount(0, replacement.length()));
        float tc = (-deltaPt / fontSize) * (1000f / units);

        int stringOperandIndex = op - 1;
        tokens.add(stringOperandIndex, Operator.getOperator("Tc"));
        tokens.add(stringOperandIndex, new org.apache.pdfbox.cos.COSFloat(tc));
        /* After two inserts original Tj shifted by +2 */
        int tjIndexAfter = op + 2;
        int resetInsertion = tjIndexAfter + 1;
        tokens.add(resetInsertion, Operator.getOperator("Tc"));
        tokens.add(resetInsertion, new org.apache.pdfbox.cos.COSFloat(0f));

        LOGGER.trace("Injected transient Tc={} for width repair at token index {}", tc, stringOperandIndex);
    }

    /**
     * Step 1: Active {@code Tw} operand immediately preceding this {@code Tj}, scanning backwards until {@code BT}.
     */
    private static float findActiveTw(List<Object> tokens, int tjOperatorIndex) {
        if (tjOperatorIndex < 1 || tjOperatorIndex >= tokens.size()) {
            return 0f;
        }
        for (int i = tjOperatorIndex - 1; i >= 0; i--) {
            Object token = tokens.get(i);
            if (token instanceof Operator op) {
                if ("BT".equals(op.getName())) {
                    return 0f;
                }
                if ("Tw".equals(op.getName()) && i >= 1) {
                    Float w = numericTokenFloat(tokens.get(i - 1));
                    if (w != null) {
                        return w;
                    }
                }
            }
        }
        return 0f;
    }

    private static Float numericTokenFloat(Object token) {
        return token instanceof COSNumber n ? n.floatValue() : null;
    }

    private static int countAsciiSpaces(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == ' ') {
                n++;
            }
        }
        return n;
    }

    /**
     * Gate 4: Reject TAB/LF/NBSP and other whitespace that is not U+0020 so {@code Tw} semantics stay predictable.
     */
    private static boolean hasOnlyAsciiSpaceWhitespace(String replacement) {
        if (replacement == null) {
            return true;
        }
        int i = 0;
        while (i < replacement.length()) {
            int cp = replacement.codePointAt(i);
            if (Character.isWhitespace(cp) && cp != ' ') {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    /**
     * Steps 4–5: Decide whether transient {@code Tw} is suitable for this segment and replacement widths.
     */
    private static boolean shouldApplyTw(
            TextSegment segment,
            String matchedOriginal,
            String replacement,
            PDFont matchedFont,
            PDFont replacementFont,
            float fontSize
    ) throws IOException {
        if (segment.parentArray != null || replacementFont == null) {
            return false;
        }
        if (countAsciiSpaces(replacement) <= 0 || !hasOnlyAsciiSpaceWhitespace(replacement)) {
            return false;
        }
        try {
            replacementFont.encode(" ");
        } catch (IllegalArgumentException e) {
            return false;
        }
        float wOld = stringWidthUserSpace(matchedOriginal == null ? "" : matchedOriginal, matchedFont, fontSize);
        float wNew = stringWidthUserSpace(replacement == null ? "" : replacement, replacementFont, fontSize);
        return Math.abs(wNew - wOld) >= TW_WIDTH_DIFF_THRESHOLD_PT;
    }

    /**
     * Step 2: Per-U+0020 adjustment in glyph-width units (~ PDFBox {@code getStringWidth} scale / 1000 em),
     * to cancel width delta when distributed only across ASCII spaces.
     */
    private static float calculateTwAdjustment(
            String matchedOriginal,
            String replacement,
            PDFont matchedFont,
            PDFont replacementFont,
            float fontSize,
            int replacementSpaceCount
    ) throws IOException {
        if (replacementSpaceCount <= 0 || fontSize <= 0.01f) {
            return 0f;
        }
        float diffPt =
                stringWidthUserSpace(replacement, replacementFont, fontSize)
                        - stringWidthUserSpace(matchedOriginal, matchedFont, fontSize);
        return (-diffPt / replacementSpaceCount) / fontSize * 1000f;
    }

    /**
     * Step 3: {@code [(Tw0+Δ) Tw ( … ) Tj Tw0 Tw]} fencing so following text sees the prior word spacing again.
     */
    private static boolean applyTwAdjustment(
            List<Object> tokens,
            TextSegment segment,
            String matchedOriginal,
            String replacement,
            PDFont matchedFont,
            PDFont replacementFont
    ) throws IOException {
        if (!shouldApplyTw(segment, matchedOriginal, replacement, matchedFont, replacementFont, estimateFontSizeHint(segment))) {
            return false;
        }
        int stringIndex = resolveStandaloneTjStringTokenIndex(tokens, segment);
        if (stringIndex < 0 || stringIndex + 1 >= tokens.size()) {
            return false;
        }
        if (!(tokens.get(stringIndex + 1) instanceof Operator op) || !"Tj".equals(op.getName())) {
            return false;
        }
        int tjIndex = stringIndex + 1;

        float fontSize = estimateFontSizeHint(segment);
        float existingTw = findActiveTw(tokens, tjIndex);
        int spaces = countAsciiSpaces(replacement);
        float delta = calculateTwAdjustment(matchedOriginal, replacement, matchedFont, replacementFont, fontSize, spaces);
        if (Math.abs(delta) < 1e-5f) {
            return false;
        }
        float combined = clampTw(existingTw + delta);

        /* Highest index first: restore pair after {@code Tj} */
        tokens.add(tjIndex + 1, Operator.getOperator("Tw"));
        tokens.add(tjIndex + 1, new org.apache.pdfbox.cos.COSFloat(existingTw));
        tokens.add(stringIndex, Operator.getOperator("Tw"));
        tokens.add(stringIndex, new org.apache.pdfbox.cos.COSFloat(combined));

        LOGGER.trace(
                "Injected transient Tw fencing originalTw={}, combinedTw={}, delta={}, spaces={}",
                existingTw,
                combined,
                delta,
                spaces);
        return true;
    }

    private static float clampTw(float tw) {
        if (tw > TW_MAX_COMBINED_MAGNITUDE) {
            return TW_MAX_COMBINED_MAGNITUDE;
        }
        if (tw < -TW_MAX_COMBINED_MAGNITUDE) {
            return -TW_MAX_COMBINED_MAGNITUDE;
        }
        return tw;
    }

    private static int resolveStandaloneTjStringTokenIndex(List<Object> tokens, TextSegment segment) {
        if (tokens == null || segment == null) {
            return -1;
        }
        COSString needle = segment.cosString;
        for (int i = 0; i < tokens.size() - 1; i++) {
            if (tokens.get(i) == needle && tokens.get(i + 1) instanceof Operator op && "Tj".equals(op.getName())) {
                return i;
            }
        }
        return -1;
    }

    private static List<TextSegment> findSameBaselineRewriteRun(List<TextSegment> allSegments, List<TextSegment> affected) {
        TextSegment first = affected.get(0);
        TextSegment last = affected.get(affected.size() - 1);
        int firstIndex = allSegments.indexOf(first);
        int lastIndex = allSegments.indexOf(last);
        if (firstIndex < 0 || lastIndex < 0) {
            return affected;
        }

        List<TextSegment> run = new ArrayList<>();
        TextSegment previous = null;
        for (int i = firstIndex; i < allSegments.size(); i++) {
            TextSegment candidate = allSegments.get(i);
            if (previous != null && !isContiguousTextRun(previous, candidate)) {
                break;
            }
            run.add(candidate);
            previous = candidate;
        }
        return run;
    }

    private static boolean isContiguousTextRun(TextSegment previous, TextSegment candidate) {
        int operatorGap = candidate.operatorIndex - previous.operatorIndex;
        if (operatorGap <= 0 || operatorGap > 25) {
            return false;
        }
        if (candidate.x + 2.0f < previous.x) {
            return false;
        }

        float gap = candidate.x - previous.x;
        float expectedAdvance = estimateSegmentAdvance(previous);
        float extraGap = gap - expectedAdvance;
        if (extraGap > largeFieldGapThreshold(previous)) {
            return false;
        }

        String text = candidate.originalText;
        return !text.contains("\n") && !text.contains("\r");
    }

    private static float estimateSegmentAdvance(TextSegment segment) {
        if (segment.text == null || segment.text.isEmpty()) {
            return 0;
        }
        try {
            float fontSize = segment.fontSize instanceof COSNumber size ? size.floatValue() : 12.0f;
            return segment.font.getStringWidth(segment.originalText) / 1000.0f * fontSize;
        } catch (IOException | IllegalArgumentException e) {
            return 0;
        }
    }

    private static float largeFieldGapThreshold(TextSegment segment) {
        float fontSize = segment.fontSize instanceof COSNumber size ? size.floatValue() : 12.0f;
        return Math.max(12.0f, fontSize * 1.2f);
    }

    private static int applyRunReconstruction(
            List<TextSegment> rewriteRun,
            int matchStart,
            int matchEnd,
            String replacement,
            PDFont substituteFont,
            boolean preserveStyle
    ) throws IOException {
        TextSegment first = rewriteRun.get(0);
        TextSegment last = rewriteRun.get(rewriteRun.size() - 1);
        StringBuilder originalRun = new StringBuilder();
        for (TextSegment segment : rewriteRun) {
            originalRun.append(segment.originalText);
        }

        int localMatchStart = Math.max(0, matchStart - first.start);
        int localMatchEnd = Math.min(originalRun.length(), matchEnd - first.start);
        String rebuiltRun = originalRun.substring(0, localMatchStart)
                + replacement
                + originalRun.substring(localMatchEnd);

        rewriteSegment(first, rebuiltRun, substituteFont, preserveStyle);
        int changed = 1;
        for (int i = 1; i < rewriteRun.size(); i++) {
            rewriteSegment(rewriteRun.get(i), "", substituteFont, preserveStyle);
            changed++;
        }

        return changed;
    }

    private static void shiftFollowingPositionedText(
            List<Object> tokens,
            List<TextSegment> allSegments,
            List<TextSegment> affected,
            String replacement,
            PDFont replacementFont,
            String matchedOriginal
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
        float delta = estimateReplacementDelta(tokens, affected, replacement, replacementFont, matchedOriginal);
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
            PDFont replacementFont,
            String matchedOriginal
    ) throws IOException {
        TextSegment first = affected.get(0);
        float laidOutWidth = 0;
        for (int i = 1; i < affected.size(); i++) {
            laidOutWidth += precedingTdX(tokens, affected.get(i));
        }

        int afterLastOperator = affected.get(affected.size() - 1).operatorIndex;
        if (afterLastOperator + 2 < tokens.size()
                && tokens.get(afterLastOperator + 2) instanceof Operator operator
                && "Td".equals(operator.getName())
                && tokens.get(afterLastOperator + 1) instanceof COSNumber dy
                && Math.abs(dy.floatValue()) < 0.001f
                && tokens.get(afterLastOperator + 1 - 1) instanceof COSNumber dx) {
            laidOutWidth += dx.floatValue();
        }

        StringBuilder contiguousOriginal = new StringBuilder();
        for (TextSegment segment : affected) {
            contiguousOriginal.append(segment.originalText);
        }
        String contiguous = contiguousOriginal.toString();

        String match = matchedOriginal != null ? matchedOriginal : contiguous;
        if (match.isEmpty() && contiguous.isEmpty()) {
            return 0;
        }

        float denom;
        try {
            denom = Math.max(1f, first.font.getStringWidth(contiguous));
        } catch (IllegalArgumentException ex) {
            denom = Math.max(1f, 550f * Math.max(1, contiguous.codePointCount(0, contiguous.length())));
        }

        float glyphScale;
        float fontSize = estimateFontSizeHint(first);
        if (laidOutWidth > 0f) {
            glyphScale = laidOutWidth / denom;
        } else {
            /* No measured Tj gaps: map PDFBox widths (text units × 1000) into user offsets via fontSize */
            glyphScale = fontSize / 1000f;
        }

        try {
            float oldMatch = first.font.getStringWidth(match) * glyphScale;
            float newMatch = replacementFont.getStringWidth(replacement) * glyphScale;
            return newMatch - oldMatch;
        } catch (IllegalArgumentException e) {
            if (laidOutWidth > 0f) {
                int cl = Math.max(1, contiguous.length());
                float share = laidOutWidth / cl;
                return share * (replacement.length() - match.length());
            }
            return 0.48f * fontSize * (replacement.length() - match.length());
        }
    }

    private static float estimateFontSizeHint(TextSegment segment) {
        return segment.fontSize instanceof COSNumber n ? Math.max(0.1f, n.floatValue()) : 12f;
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
            PDFont substituteFont,
            boolean preserveStyle
    ) throws IOException {
        TextSegment first = affected.get(0);
        PDFont replacementFont = chooseEncodingFont(first.font, replacement, substituteFont, preserveStyle);
        first.dynamicReplacementText = replacement;
        first.dynamicReplacementFont = replacementFont;
        first.dynamicUsesSubstituteFont = replacementFont == substituteFont;

        int changed = 0;
        for (TextSegment segment : affected) {
            int localStart = Math.max(0, matchStart - segment.start);
            int localEnd = Math.min(segment.text.length(), matchEnd - segment.start);
            String newText = segment.text.substring(0, localStart) + segment.text.substring(localEnd);
            rewriteSegment(segment, newText, null, preserveStyle);
            changed++;
        }

        if (!preserveStyle) {
            zeroInternalArraySpacing(affected);
        }
        return changed;
    }

    private static PDFont chooseEncodingFont(PDFont originalFont, String text, PDFont substituteFont, boolean preserveStyle) throws IOException {
        /* Subset fonts are unsafe for arbitrary replacement strings — prefer substitute when present. */
        if (substituteFont != null && isSubsetFont(originalFont)) {
            substituteFont.encode(text);
            return substituteFont;
        }
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

    private static void rewriteSegment(TextSegment segment, String newText, PDFont substituteFont, boolean preserveStyle) throws IOException {
        if (segment.font instanceof PDType3Font) {
            throw new IOException("Type3 fonts are not supported by this baseline replacer");
        }
        if (substituteFont != null && isSubsetFont(segment.font)) {
            try {
                segment.cosString.setValue(substituteFont.encode(newText));
                segment.usesSubstituteFont = true;
                if (preserveStyle && !isStyleCompatible(segment.font, substituteFont)) {
                    segment.styleApproximationUsed = true;
                }
                segment.text = newText;
                segment.changed = true;
                LOGGER.debug(
                        "Rewrote subset font segment using substitute; pdfFont={}, substitute={}",
                        safeFontName(segment.font),
                        safeFontName(substituteFont));
                return;
            } catch (IllegalArgumentException e) {
                throw new IOException("Subset font '" + safeFontName(segment.font) + "' requires a substitute, but substitution text cannot be encoded. "
                        + "Preview: [" + previewText(newText) + "]. Ensure server fonts cover these characters.", e);
            }
        }
        try {
            segment.cosString.setValue(segment.font.encode(newText));
            segment.usesSubstituteFont = false;
        } catch (IllegalArgumentException e) {
            String originalFontName = safeFontName(segment.font);
            String preview = previewText(newText);
            if (substituteFont == null) {
                String subsetHint =
                        isSubsetFont(segment.font)
                                ? " Embedded font is subset (likely missing glyphs for new text)."
                                : " Embedded font encoding failed.";
                throw new IOException("Replacement text cannot be encoded with the original font '" + originalFontName + "'." + subsetHint
                        + " Failed text preview: [" + preview + "]. "
                        + "Try characters already embedded in this PDF or install Unicode fonts on the server.", e);
            }
            try {
                segment.cosString.setValue(substituteFont.encode(newText));
                segment.usesSubstituteFont = true;
                if (preserveStyle && !isStyleCompatible(segment.font, substituteFont)) {
                    segment.styleApproximationUsed = true;
                }
            } catch (IllegalArgumentException fallbackError) {
                throw new IOException("Replacement text cannot be encoded with original font '" + originalFontName + "' "
                        + "or fallback font '" + safeFontName(substituteFont) + "'. "
                        + "Failed text preview: [" + preview + "]. "
                        + "Install a font with the required glyphs on the server (see logs for missing candidate paths).", fallbackError);
            }
        }
        segment.text = newText;
        segment.changed = true;
    }

    private static String safeFontName(PDFont font) {
        return font == null ? "unknown-font" : font.getName();
    }

    private static String previewText(String text) {
        if (text == null) {
            return "";
        }
        String compact = text.replace("\n", " ").replace("\r", " ");
        return compact.length() <= 40 ? compact : compact.substring(0, 40) + "...";
    }

    private static PDFont chooseStyleCompatibleSubstituteFont(
            PDDocument document,
            PDFont currentSubstitute,
            String replacement,
            List<TextSegment> segments,
            List<Match> matches,
            boolean preserveStyle
    ) throws IOException {
        if (!preserveStyle || currentSubstitute == null || matches.isEmpty()) {
            return currentSubstitute;
        }
        TextSegment firstAffected = findFirstAffectedSegment(segments, matches.get(matches.size() - 1));
        if (firstAffected == null || isStyleCompatible(firstAffected.font, currentSubstitute)) {
            return currentSubstitute;
        }
        PDFont styleFont = loadStyleCompatibleSubstituteFont(document, replacement, firstAffected.font);
        return styleFont == null ? currentSubstitute : styleFont;
    }

    private static TextSegment findFirstAffectedSegment(List<TextSegment> segments, Match match) {
        for (TextSegment segment : segments) {
            if (segment.end > match.start() && segment.start < match.end()) {
                return segment;
            }
        }
        return null;
    }

    private static boolean isStyleCompatible(PDFont originalFont, PDFont fallbackFont) {
        FontStyle original = detectStyle(safeFontName(originalFont));
        FontStyle fallback = detectStyle(safeFontName(fallbackFont));
        return (!original.effectiveBold() || fallback.effectiveBold())
                && (!original.italic() || fallback.italic());
    }

    private static FontStyle detectStyle(String fontName) {
        String value = fontName == null ? "" : fontName.toLowerCase(Locale.ROOT);
        boolean italic = value.contains("italic") || value.contains("oblique");
        boolean bold =
                value.contains("bold")
                        || value.contains("black")
                        || value.contains("heavy")
                        || value.contains("demi")
                        || value.contains("semibold")
                        || value.contains("semi-bold")
                        || value.contains("demibold")
                        || value.contains("extrabold")
                        || value.contains("ultrabold")
                        || value.contains("fat");
        boolean light = value.contains("light") || value.contains("thin") || value.contains("hairline");
        return new FontStyle(bold && !light, italic, light);
    }

    private static PDType0Font loadSubstituteFont(
            PDDocument document,
            File cliFontFile,
            String replacement
    ) throws IOException {
        Set<String> missingLogged = new HashSet<>();
        List<File> candidates = new ArrayList<>();
        if (cliFontFile != null) {
            if (!cliFontFile.isFile()) {
                LOGGER.warn("CLI substitute font path not found or not a file: {}", cliFontFile.getAbsolutePath());
            } else {
                candidates.add(cliFontFile);
            }
        }

        // Linux paths (typical Docker/Ubuntu packages)
        candidates.add(new File("/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf"));
        candidates.add(new File("/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf"));
        candidates.add(new File("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"));
        candidates.add(new File("/usr/share/fonts/truetype/freefont/FreeSans.ttf"));
        candidates.add(new File("/usr/share/fonts/truetype/open-sans/OpenSans-Regular.ttf"));
        // macOS paths (local dev)
        candidates.add(new File("/System/Library/Fonts/Supplemental/Arial.ttf"));
        candidates.add(new File("/Library/Fonts/Arial Unicode.ttf"));
        // Local paths
        candidates.add(new File("fonts/NotoSans-Regular.ttf"));
        candidates.add(new File("src/main/resources/fonts/NotoSans-Regular.ttf"));

        for (File candidate : candidates) {
            PDType0Font loaded = tryLoadEncodedSubstitute(document, candidate, replacement, missingLogged);
            if (loaded != null) {
                LOGGER.debug("Using curated substitute font: {}", candidate.getAbsolutePath());
                return loaded;
            }
        }

        PDType0Font discovered = discoverSubstituteFont(document, replacement, null);
        if (discovered != null) {
            return discovered;
        }

        LOGGER.warn("No substitute font could encode the replacement text after curated paths and similarity discovery.");
        return null;
    }

    /**
     * Curated bold/italic/Roman candidates, then similarity-ranked scan of installed fonts.
     */
    private static PDType0Font loadStyleCompatibleSubstituteFont(
            PDDocument document,
            String replacement,
            PDFont originalFont
    ) throws IOException {
        FontStyle style = detectStyle(safeFontName(originalFont));
        List<File> candidates = new ArrayList<>();

        LinkedHashSet<String> seenAbsolute = new LinkedHashSet<>();
        for (String pathStr : FontSimilarityMap.getCandidatePaths(safeFontName(originalFont))) {
            if (seenAbsolute.add(pathStr)) {
                candidates.add(new File(pathStr));
            }
        }

        if (style.effectiveBold() && style.italic()) {
            candidates.add(new File("/usr/share/fonts/truetype/liberation/LiberationSans-BoldItalic.ttf"));
            candidates.add(new File("/usr/share/fonts/truetype/noto/NotoSans-BoldItalic.ttf"));
            candidates.add(new File("/usr/share/fonts/truetype/freefont/FreeSansBoldOblique.ttf"));
            candidates.add(new File("/usr/share/fonts/truetype/open-sans/OpenSans-BoldItalic.ttf"));
            candidates.add(new File("/usr/share/fonts/truetype/open-sans/OpenSans-SemiboldItalic.ttf"));
            candidates.add(new File("/usr/share/fonts/truetype/open-sans/OpenSans-ExtraBoldItalic.ttf"));
            candidates.add(new File("/System/Library/Fonts/Supplemental/Arial Bold Italic.ttf"));
        }
        if (style.effectiveBold()) {
            candidates.add(new File("/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"));
            candidates.add(new File("/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf"));
            candidates.add(new File("/usr/share/fonts/truetype/freefont/FreeSansBold.ttf"));
            candidates.add(new File("/usr/share/fonts/truetype/open-sans/OpenSans-Bold.ttf"));
            candidates.add(new File("/usr/share/fonts/truetype/open-sans/OpenSans-Semibold.ttf"));
            candidates.add(new File("/usr/share/fonts/truetype/open-sans/OpenSans-ExtraBold.ttf"));
            candidates.add(new File("/System/Library/Fonts/Supplemental/Arial Bold.ttf"));
        }
        if (style.italic()) {
            candidates.add(new File("/usr/share/fonts/truetype/liberation/LiberationSans-Italic.ttf"));
            candidates.add(new File("/usr/share/fonts/truetype/noto/NotoSans-Italic.ttf"));
            candidates.add(new File("/usr/share/fonts/truetype/freefont/FreeSansOblique.ttf"));
            candidates.add(new File("/usr/share/fonts/truetype/open-sans/OpenSans-Italic.ttf"));
            candidates.add(new File("/System/Library/Fonts/Supplemental/Arial Italic.ttf"));
        }
        // Regular fallbacks
        candidates.add(new File("/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf"));
        candidates.add(new File("/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf"));
        candidates.add(new File("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"));
        candidates.add(new File("/usr/share/fonts/truetype/freefont/FreeSans.ttf"));
        candidates.add(new File("/usr/share/fonts/truetype/open-sans/OpenSans-Regular.ttf"));
        candidates.add(new File("/System/Library/Fonts/Supplemental/Arial.ttf"));
        candidates.add(new File("fonts/NotoSans-Regular.ttf"));
        candidates.add(new File("src/main/resources/fonts/NotoSans-Regular.ttf"));

        Set<String> missingLogged = new HashSet<>();
        for (File candidate : candidates) {
            PDType0Font loaded = tryLoadEncodedSubstitute(document, candidate, replacement, missingLogged);
            if (loaded != null) {
                LOGGER.debug("Using style-aware curated substitute font: {}", candidate.getAbsolutePath());
                return loaded;
            }
        }

        return discoverSubstituteFont(document, replacement, originalFont);
    }

    private static void logMissingFontCandidate(File candidate, Set<String> missingLogged) {
        String path = candidate.getAbsolutePath();
        if (missingLogged.add(path)) {
            LOGGER.info("Substitute font candidate not found on disk (install package or add file if needed): {}", path);
        }
    }

    private static PDType0Font tryLoadEncodedSubstitute(
            PDDocument document,
            File candidate,
            String replacement,
            Set<String> missingLogged
    ) {
        if (!candidate.isFile()) {
            logMissingFontCandidate(candidate, missingLogged);
            return null;
        }
        try {
            PDType0Font font = PDType0Font.load(document, candidate);
            font.encode(replacement);
            return font;
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.debug("Substitute font cannot encode replacement, skipping {}: {}", candidate.getAbsolutePath(), e.getMessage());
            return null;
        }
    }

    /**
     * Heuristic similarity between embedded PDF font name and an on-disk font filename (tokens + bold/italic).
     * Higher is better.
     */
    static double substituteSimilarityScore(PDFont referenceFont, Path fontPath) {
        if (referenceFont == null || fontPath == null) {
            return 0.0;
        }
        String refLabel = FontSimilarityMap.normalizedFontKey(safeFontName(referenceFont));
        String fileLabel = FontSimilarityMap.normalizedFontKey(
                fontPath.getFileName().toString().replaceFirst("(?i)\\.(ttf|otf)$", ""));
        if (refLabel.isBlank() || fileLabel.isBlank()) {
            return 0.0;
        }
        FontStyle refStyle = detectStyle(refLabel);
        FontStyle pathStyle = detectStyle(fileLabel);
        double jaccard = tokenJaccard(refLabel, fileLabel);
        double score = jaccard;
        // Style alignment with PDF font metadata
        if (refStyle.effectiveBold() == pathStyle.effectiveBold()) {
            score += 0.12;
        } else if (refStyle.effectiveBold() && !pathStyle.effectiveBold()) {
            score -= 0.08;
        }
        if (refStyle.italic() == pathStyle.italic()) {
            score += 0.12;
        } else if (refStyle.italic() && !pathStyle.italic()) {
            score -= 0.08;
        }
        return score;
    }

    private static double tokenJaccard(String a, String b) {
        Set<String> ta = splitFontTokens(a);
        Set<String> tb = splitFontTokens(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return 0.0;
        }
        Set<String> inter = ta.stream().filter(tb::contains).collect(Collectors.toSet());
        Set<String> union = new HashSet<>(ta);
        union.addAll(tb);
        return union.isEmpty() ? 0.0 : (double) inter.size() / (double) union.size();
    }

    private static Set<String> splitFontTokens(String raw) {
        String[] parts = raw.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        Set<String> out = new HashSet<>();
        for (String p : parts) {
            if (p.length() >= 2) {
                out.add(p);
            }
        }
        return out;
    }

    private static List<Path> listInstallableFontPaths() {
        List<Path> roots = defaultFontRoots();
        String extra = System.getProperty(EXTRA_FONT_ROOTS_PROP, "");
        if (extra != null && !extra.isBlank()) {
            for (String part : extra.split(java.io.File.pathSeparator)) {
                if (part != null && !part.isBlank()) {
                    roots.add(Path.of(part.trim()));
                }
            }
        }
        List<Path> fonts = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                LOGGER.trace("Font scan root missing or not a directory: {}", root.toAbsolutePath());
                continue;
            }
            try (Stream<Path> walk = Files.walk(root, 6)) {
                walk.filter(p -> Files.isRegularFile(p) && isFontExtension(p)).forEach(fonts::add);
            } catch (IOException e) {
                LOGGER.debug("Unable to scan font directory {}: {}", root.toAbsolutePath(), e.getMessage());
            }
        }
        fonts.sort(Comparator.comparing(Path::toString));
        return fonts;
    }

    private static List<Path> defaultFontRoots() {
        List<Path> list = new ArrayList<>();
        list.add(Path.of("/usr/share/fonts"));
        list.add(Path.of("/usr/local/share/fonts"));
        list.add(Path.of("fonts"));
        list.add(Path.of("src/main/resources/fonts"));
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("windows")) {
            String windir = System.getenv("WINDIR");
            if (windir != null && !windir.isBlank()) {
                list.add(Path.of(windir, "Fonts"));
            }
        }
        if (os.contains("mac")) {
            list.add(Path.of("/System/Library/Fonts/Supplemental"));
            list.add(Path.of("/Library/Fonts"));
        }
        return list;
    }

    private static boolean isFontExtension(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".ttf") || n.endsWith(".otf");
    }

    /**
     * After curated candidates fail, try installed fonts ranked by similarity to {@code referenceFont}
     * (or lexicographic order when referenceFont is null).
     */
    private static PDType0Font discoverSubstituteFont(
            PDDocument document,
            String replacement,
            PDFont referenceFont
    ) throws IOException {
        List<Path> all = listInstallableFontPaths();
        if (all.isEmpty()) {
            LOGGER.warn("substitute-discovery: No .ttf/.otf files found under scan roots — add fonts under /usr/share/fonts or {}",
                    EXTRA_FONT_ROOTS_PROP);
            return null;
        }

        record Scored(Path path, double score) {
        }

        Comparator<Scored> order = Comparator.<Scored>comparingDouble(Scored::score).reversed()
                .thenComparing(s -> s.path.toString());

        List<Scored> ranked = all.stream()
                .map(path -> new Scored(path, substituteSimilarityScore(referenceFont, path)))
                .sorted(order)
                .toList();

        int attempts = 0;
        boolean verboseRank = LOGGER.isDebugEnabled();

        for (Scored scored : ranked) {
            if (attempts >= MAX_FONT_DISCOVERY_PROBE) {
                break;
            }
            attempts++;
            if (verboseRank && attempts <= 5 && referenceFont != null) {
                LOGGER.debug(
                        "discovery rank sample #{} score={} path={}",
                        attempts,
                        String.format(Locale.ROOT, "%.4f", scored.score),
                        scored.path);
            }
            try {
                File file = scored.path.toFile();
                PDType0Font font = PDType0Font.load(document, file);
                font.encode(replacement);
                LOGGER.info(
                        "Using discovery-selected substitute font path={} similarityScore={} referenceFont={}",
                        scored.path.toAbsolutePath(),
                        String.format(Locale.ROOT, "%.4f", scored.score),
                        referenceFont == null ? "(none)" : safeFontName(referenceFont));
                return font;
            } catch (IOException | IllegalArgumentException e) {
                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace("discovery skip {}: {}", scored.path, e.getMessage());
                }
            }
        }

        LOGGER.warn(
                "substitute-discovery: No font could encode the replacement text after probing {} of {} scanned files{}",
                Math.min(MAX_FONT_DISCOVERY_PROBE, ranked.size()),
                ranked.size(),
                referenceFont == null ? "" : " (reference font: " + safeFontName(referenceFont) + ")");
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
            if (segment.dynamicUsesSubstituteFont && segment.fontResourceName != null) {
                tokens.add(insertBefore + 5, segment.fontResourceName);
                tokens.add(insertBefore + 6, segment.fontSize);
                tokens.add(insertBefore + 7, Operator.getOperator("Tf"));
            }
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

    private record FontStyle(boolean bold, boolean italic, boolean light) {
        boolean effectiveBold() {
            return bold && !light;
        }
    }

    /**
     * True when PDF font name uses a subset tag (typically 6 alphanumeric chars + '+' per PDF conventions).
     * Subset embeddings often omit glyphs not used in the source document; substitutions should bypass them.
     */
    private static boolean isSubsetFont(PDFont font) {
        String name = font != null ? font.getName() : null;
        if (name == null || name.length() < 8) {
            return false;
        }
        if (name.charAt(6) != '+') {
            return false;
        }
        for (int i = 0; i < 6; i++) {
            char c = name.charAt(i);
            if (!(Character.isUpperCase(c) || (c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }

    private static final class TextSegment {
        private final COSString cosString;
        private final PDFont font;
        private final COSArray parentArray;
        private final int arrayIndex;
        private final int operatorIndex;
        private final COSName fontResourceName;
        private final COSBase fontSize;
        private final float x;
        private final float y;
        private final String originalText;
        private String text;
        private int start;
        private int end;
        private boolean usesSubstituteFont;
        private String dynamicReplacementText;
        private PDFont dynamicReplacementFont;
        private boolean dynamicUsesSubstituteFont;
        private float dynamicShiftApplied;
        private boolean changed;
        private boolean styleApproximationUsed;

        private TextSegment(
                COSString cosString,
                PDFont font,
                String text,
                COSArray parentArray,
                int arrayIndex,
                int operatorIndex,
                COSName fontResourceName,
                COSBase fontSize,
                float x,
                float y
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
            this.x = x;
            this.y = y;
        }
    }
}
