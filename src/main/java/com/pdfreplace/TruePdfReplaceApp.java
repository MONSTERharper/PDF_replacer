package com.pdfreplace;

import java.io.File;

public final class TruePdfReplaceApp {
    private TruePdfReplaceApp() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: mvn exec:java -Dexec.mainClass=com.pdfreplace.TruePdfReplaceApp "
                    + "-Dexec.args=\"input.pdf output.pdf search replacement [--strict] [--font /path/to/font.ttf]\"");
            System.exit(2);
        }

        File input = new File(args[0]);
        File output = new File(args[1]);
        String search = args[2];
        String replacement = args[3];
        boolean strictSameLength = false;
        File substituteFontFile = null;
        for (int i = 4; i < args.length; i++) {
            if ("--strict".equals(args[i])) {
                strictSameLength = true;
            } else if ("--font".equals(args[i]) && i + 1 < args.length) {
                substituteFontFile = new File(args[++i]);
            } else {
                throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }

        DeterministicPdfReplacer.Result result = DeterministicPdfReplacer.replace(
                input,
                output,
                search,
                replacement,
                strictSameLength,
                substituteFontFile
        );

        System.out.println("Pages scanned: " + result.pagesScanned());
        System.out.println("Matches replaced: " + result.matchesReplaced());
        System.out.println("Segments changed: " + result.segmentsChanged());
        System.out.println("Output: " + output.getAbsolutePath());
    }
}
