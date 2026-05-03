package com.pdfreplace;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;

public final class AccuracyCheckApp {
    private AccuracyCheckApp() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: mvn exec:java -Dexec.mainClass=com.pdfreplace.AccuracyCheckApp "
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

        String outputText = extractText(output);
        boolean oldStillPresent = outputText.contains(search);
        boolean replacementPresent = outputText.contains(replacement);

        System.out.println("Pages scanned: " + result.pagesScanned());
        System.out.println("Matches replaced: " + result.matchesReplaced());
        System.out.println("Segments changed: " + result.segmentsChanged());
        System.out.println("Old text still extractable: " + oldStillPresent);
        System.out.println("Replacement extractable: " + replacementPresent);

        if (result.matchesReplaced() == 0 || oldStillPresent || !replacementPresent) {
            System.err.println("Accuracy check failed.");
            System.exit(1);
        }

        System.out.println("Accuracy check passed.");
    }

    private static String extractText(File file) throws Exception {
        try (PDDocument document = PDDocument.load(file)) {
            return new PDFTextStripper().getText(document);
        }
    }
}
