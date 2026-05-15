package com.pdfreplace;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps embedded PDF font name patterns (after normalization) to similar open fonts on Ubuntu/Docker/macOS paths.
 */
public final class FontSimilarityMap {

    // Based on CSS font stacks — metric-compatible pairs first where possible.
    private static final Map<String, List<String>> SIMILARITY_MAP = new LinkedHashMap<>();

    static {
        // ── Serif ──
        put("times-roman", List.of("LiberationSerif-Regular", "NotoSerif-Regular", "FreeSerif", "DejaVuSerif"));
        put("times-bold", List.of("LiberationSerif-Bold", "NotoSerif-Bold", "FreeSerifBold", "DejaVuSerif-Bold"));
        put("times-italic", List.of("LiberationSerif-Italic", "NotoSerif-Italic", "FreeSerifItalic"));
        put("times-bolditalic", List.of("LiberationSerif-BoldItalic", "NotoSerif-BoldItalic", "FreeSerifBoldItalic"));
        put("times-new-roman", List.of("LiberationSerif-Regular", "NotoSerif-Regular", "FreeSerif"));
        put("georgia", List.of("NotoSerif-Regular", "LiberationSerif-Regular", "DejaVuSerif"));
        put("georgia-bold", List.of("NotoSerif-Bold", "LiberationSerif-Bold", "DejaVuSerif-Bold"));
        put("garamond", List.of("NotoSerif-Regular", "LiberationSerif-Regular", "FreeSerif"));
        put("palatino", List.of("NotoSerif-Regular", "FreeSerif", "LiberationSerif-Regular"));
        put("cambria", List.of("NotoSerif-Regular", "LiberationSerif-Regular", "DejaVuSerif"));
        put("courier",
                List.of("LiberationMono-Regular", "NotoSansMono-Regular", "FreeMono", "DejaVuSansMono"));
        put("courier-bold", List.of("LiberationMono-Bold", "NotoSansMono-Bold", "DejaVuSansMono-Bold"));
        put("courier-oblique",
                List.of("LiberationMono-Italic", "FreeMonoOblique", "DejaVuSansMono-Oblique"));
        put("courier-boldoblique",
                List.of("LiberationMono-BoldItalic", "DejaVuSansMono-BoldOblique", "FreeMonoBoldOblique"));
        put("courier-new", List.of("LiberationMono-Regular", "NotoSansMono-Regular", "FreeMono"));

        // ── Sans-serif ──
        put("helvetica", List.of("LiberationSans-Regular", "NotoSans-Regular", "FreeSans", "OpenSans-Regular"));
        put("helvetica-bold",
                List.of("LiberationSans-Bold", "NotoSans-Bold", "FreeSansBold", "OpenSans-Bold"));
        put("helvetica-oblique",
                List.of("LiberationSans-Italic", "NotoSans-Italic", "FreeSansOblique", "OpenSans-Italic"));
        put("helvetica-boldoblique",
                List.of("LiberationSans-BoldItalic", "NotoSans-BoldItalic",
                        "FreeSansBoldOblique", "OpenSans-BoldItalic"));
        put("arial", List.of("LiberationSans-Regular", "NotoSans-Regular", "FreeSans", "OpenSans-Regular"));
        put("arial-bold", List.of("LiberationSans-Bold", "NotoSans-Bold", "FreeSansBold", "OpenSans-Bold"));
        put("arial-italic", List.of("LiberationSans-Italic", "NotoSans-Italic",
                "FreeSansOblique", "OpenSans-Italic"));
        put("arial-bolditalic", List.of("LiberationSans-BoldItalic", "NotoSans-BoldItalic",
                "FreeSansBoldOblique", "OpenSans-BoldItalic"));
        put("calibri", List.of("NotoSans-Regular", "OpenSans-Regular", "LiberationSans-Regular"));
        put("calibri-bold",
                List.of("NotoSans-Bold", "OpenSans-Bold", "LiberationSans-Bold"));
        put("verdana", List.of("DejaVuSans", "NotoSans-Regular", "LiberationSans-Regular"));
        put("verdana-bold", List.of("DejaVuSans-Bold", "NotoSans-Bold", "LiberationSans-Bold"));
        put("tahoma", List.of("NotoSans-Regular", "LiberationSans-Regular", "DejaVuSans"));
        put("roboto",
                List.of("NotoSans-Regular", "OpenSans-Regular", "LiberationSans-Regular"));
        put("roboto-bold",
                List.of("NotoSans-Bold", "OpenSans-Bold", "LiberationSans-Bold"));
        put("open-sans",
                List.of("OpenSans-Regular", "NotoSans-Regular", "LiberationSans-Regular"));
        put("open-sans-bold",
                List.of("OpenSans-Bold", "NotoSans-Bold", "LiberationSans-Bold"));

        // ── Fallbacks ──
        put("symbol", List.of("NotoSansSymbols-Regular", "DejaVuSans", "NotoSans-Regular"));
        put("zapfdingbats",
                List.of("NotoSansSymbols-Regular", "DejaVuSans", "NotoSans-Regular"));
        put("wingdings",
                List.of("NotoSansSymbols-Regular", "DejaVuSans", "NotoSans-Regular"));
    }

    private static void put(String key, List<String> value) {
        SIMILARITY_MAP.put(key, value);
    }

    private static final Map<String, String> FONT_PATHS = new LinkedHashMap<>();

    static {
        String lib = "/usr/share/fonts/truetype/liberation/";
        String noto = "/usr/share/fonts/truetype/noto/";
        String deja = "/usr/share/fonts/truetype/dejavu/";
        String free = "/usr/share/fonts/truetype/freefont/";
        String open = "/usr/share/fonts/truetype/open-sans/";

        putPath("LiberationSans-Regular", lib + "LiberationSans-Regular.ttf");
        putPath("LiberationSans-Bold", lib + "LiberationSans-Bold.ttf");
        putPath("LiberationSans-Italic", lib + "LiberationSans-Italic.ttf");
        putPath("LiberationSans-BoldItalic", lib + "LiberationSans-BoldItalic.ttf");
        putPath("LiberationSerif-Regular", lib + "LiberationSerif-Regular.ttf");
        putPath("LiberationSerif-Bold", lib + "LiberationSerif-Bold.ttf");
        putPath("LiberationSerif-Italic", lib + "LiberationSerif-Italic.ttf");
        putPath("LiberationSerif-BoldItalic", lib + "LiberationSerif-BoldItalic.ttf");
        putPath("LiberationMono-Regular", lib + "LiberationMono-Regular.ttf");
        putPath("LiberationMono-Bold", lib + "LiberationMono-Bold.ttf");
        putPath("LiberationMono-Italic", lib + "LiberationMono-Italic.ttf");
        putPath("LiberationMono-BoldItalic", lib + "LiberationMono-BoldItalic.ttf");

        putPath("NotoSans-Regular", noto + "NotoSans-Regular.ttf");
        putPath("NotoSans-Bold", noto + "NotoSans-Bold.ttf");
        putPath("NotoSans-Italic", noto + "NotoSans-Italic.ttf");
        putPath("NotoSans-BoldItalic", noto + "NotoSans-BoldItalic.ttf");
        putPath("NotoSerif-Regular", noto + "NotoSerif-Regular.ttf");
        putPath("NotoSerif-Bold", noto + "NotoSerif-Bold.ttf");
        putPath("NotoSerif-Italic", noto + "NotoSerif-Italic.ttf");
        putPath("NotoSerif-BoldItalic", noto + "NotoSerif-BoldItalic.ttf");
        putPath("NotoSansMono-Regular", noto + "NotoSansMono-Regular.ttf");
        putPath("NotoSansMono-Bold", noto + "NotoSansMono-Bold.ttf");
        putPath("NotoSansSymbols-Regular",
                "/usr/share/fonts/truetype/noto/NotoSansSymbols-Regular.ttf");

        putPath("DejaVuSans", deja + "DejaVuSans.ttf");
        putPath("DejaVuSans-Bold", deja + "DejaVuSans-Bold.ttf");
        putPath("DejaVuSerif", deja + "DejaVuSerif.ttf");
        putPath("DejaVuSerif-Bold", deja + "DejaVuSerif-Bold.ttf");
        putPath("DejaVuSansMono", deja + "DejaVuSansMono.ttf");
        putPath("DejaVuSansMono-Bold", deja + "DejaVuSansMono-Bold.ttf");
        putPath("DejaVuSansMono-Oblique", deja + "DejaVuSansMono-Oblique.ttf");
        putPath("DejaVuSansMono-BoldOblique", deja + "DejaVuSansMono-BoldOblique.ttf");

        putPath("FreeSans", free + "FreeSans.ttf");
        putPath("FreeSansBold", free + "FreeSansBold.ttf");
        putPath("FreeSansOblique", free + "FreeSansOblique.ttf");
        putPath("FreeSansBoldOblique", free + "FreeSansBoldOblique.ttf");
        putPath("FreeSerif", free + "FreeSerif.ttf");
        putPath("FreeSerifBold", free + "FreeSerifBold.ttf");
        putPath("FreeSerifItalic", free + "FreeSerifItalic.ttf");
        putPath("FreeSerifBoldItalic", free + "FreeSerifBoldItalic.ttf");
        putPath("FreeMono", free + "FreeMono.ttf");
        putPath("FreeMonoBold", free + "FreeMonoBold.ttf");
        putPath("FreeMonoOblique", free + "FreeMonoOblique.ttf");
        putPath("FreeMonoBoldOblique", free + "FreeMonoBoldOblique.ttf");

        putPath("OpenSans-Regular", open + "OpenSans-Regular.ttf");
        putPath("OpenSans-Bold", open + "OpenSans-Bold.ttf");
        putPath("OpenSans-Italic", open + "OpenSans-Italic.ttf");
        putPath("OpenSans-BoldItalic", open + "OpenSans-BoldItalic.ttf");
        putPath("OpenSans-Light", open + "OpenSans-Light.ttf");

        // macOS supplemental (dev laptops)
        putPath("Arial-mac", "/System/Library/Fonts/Supplemental/Arial.ttf");
        putPath("Arial-Bold-mac", "/System/Library/Fonts/Supplemental/Arial Bold.ttf");
        putPath("Arial-Italic-mac", "/System/Library/Fonts/Supplemental/Arial Italic.ttf");
        putPath("Arial-BoldItalic-mac", "/System/Library/Fonts/Supplemental/Arial Bold Italic.ttf");
    }

    private static void putPath(String logical, String filesystemPath) {
        FONT_PATHS.put(logical, filesystemPath);
    }

    /** Strip PDF subset prefix and normalize for similarity lookup. Package-private for reuse in tests/engine. */
    static String normalizedFontKey(String pdfFontFullName) {
        if (pdfFontFullName == null || pdfFontFullName.isBlank()) {
            return "";
        }
        String n = pdfFontFullName.trim();
        int plus = n.indexOf('+');
        if (plus >= 0 && plus + 1 < n.length()) {
            n = n.substring(plus + 1);
        }
        n = n.toLowerCase(Locale.ROOT)
                .replace(' ', '-')
                .replace('_', '-')
                .replace(",", "");
        if (n.endsWith("-mt")) {
            n = n.substring(0, n.length() - "-mt".length());
        }
        if (n.endsWith("-ps")) {
            n = n.substring(0, n.length() - "-ps".length());
        }
        return n.isEmpty() ? "" : n;
    }

    public static List<String> getSimilarFonts(String fontName) {
        String normalized = normalizedFontKey(fontName);

        // 1. Direct match
        for (Map.Entry<String, List<String>> entry : SIMILARITY_MAP.entrySet()) {
            if (normalized.equals(entry.getKey())) {
                return entry.getValue();
            }
        }

        // 2. Key contained in normalized (e.g. "arialmt" → contains "arial")
        for (Map.Entry<String, List<String>> entry : SIMILARITY_MAP.entrySet()) {
            if (!entry.getKey().isEmpty() && normalized.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        return getStyleBasedFallback(normalized);
    }

    /** Logical font IDs known to FONT_PATHS. */
    public static String getFontPath(String logicalName) {
        return FONT_PATHS.get(logicalName);
    }

    /** Existing font files mapped from the embedded PDF font name, in priority order. */
    public static List<String> getCandidatePaths(String originalPdfFontFullName) {
        return getSimilarFonts(originalPdfFontFullName).stream()
                .map(FONT_PATHS::get)
                .filter(path -> path != null && new File(path).isFile())
                .toList();
    }

    /**
     * Add macOS supplemental Arial fallback when Helvetica/Arial substitutes are unresolved on Linux containers.
     */
    public static void appendArialMacIfApplicable(List<File> ordered, java.util.LinkedHashSet<String> seenAbsolute) {
        if (seenAbsolute == null) {
            seenAbsolute = new java.util.LinkedHashSet<>();
        }
        for (String key : List.of(
                "/System/Library/Fonts/Supplemental/Arial.ttf",
                "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
                "/System/Library/Fonts/Supplemental/Arial Italic.ttf",
                "/System/Library/Fonts/Supplemental/Arial Bold Italic.ttf")) {
            if (seenAbsolute.add(key)) {
                File f = new File(key);
                if (f.isFile()) {
                    ordered.add(f);
                }
            }
        }
    }

    static List<String> getStyleBasedFallback(String normalized) {
        boolean bold =
                normalized.contains("bold")
                        || normalized.contains("black")
                        || normalized.contains("demi")
                        || normalized.contains("heavy")
                        || normalized.contains("semibold")
                        || normalized.contains("semi-bold")
                        || normalized.contains("semibold")
                        || normalized.contains("extrabold")
                        || normalized.contains("ultrabold");
        boolean italic = normalized.contains("italic") || normalized.contains("oblique");
        boolean serif =
                normalized.contains("serif")
                        || normalized.contains("roman")
                        || normalized.contains("times")
                        || normalized.contains("garamond")
                        || normalized.contains("palatino");
        boolean mono =
                normalized.contains("mono")
                        || normalized.contains("courier")
                        || normalized.contains("console");

        if (mono && bold && italic) {
            return List.of(
                    "LiberationMono-BoldItalic", "DejaVuSansMono-BoldOblique", "FreeMonoBoldOblique");
        }
        if (mono && bold) {
            return List.of("LiberationMono-Bold", "DejaVuSansMono-Bold", "FreeMonoBold");
        }
        if (mono && italic) {
            return List.of("LiberationMono-Italic", "DejaVuSansMono-Oblique", "FreeMonoOblique");
        }
        if (mono) {
            return List.of("LiberationMono-Regular", "DejaVuSansMono", "FreeMono");
        }
        if (serif && bold && italic) {
            return List.of("LiberationSerif-BoldItalic", "NotoSerif-BoldItalic", "FreeSerifBoldItalic");
        }
        if (serif && bold) {
            return List.of("LiberationSerif-Bold", "NotoSerif-Bold", "FreeSerifBold");
        }
        if (serif && italic) {
            return List.of("LiberationSerif-Italic", "NotoSerif-Italic", "FreeSerifItalic");
        }
        if (serif) {
            return List.of("LiberationSerif-Regular", "NotoSerif-Regular", "FreeSerif");
        }
        if (bold && italic) {
            return List.of("LiberationSans-BoldItalic", "NotoSans-BoldItalic", "FreeSansBoldOblique");
        }
        if (bold) {
            return List.of("LiberationSans-Bold", "NotoSans-Bold", "FreeSansBold");
        }
        if (italic) {
            return List.of("LiberationSans-Italic", "NotoSans-Italic", "FreeSansOblique");
        }
        return genericSans();
    }

    private static List<String> genericSans() {
        return List.of("LiberationSans-Regular", "NotoSans-Regular", "DejaVuSans", "FreeSans", "OpenSans-Regular");
    }

    private FontSimilarityMap() {
    }
}
