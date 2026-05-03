package com.pdfreplace;

import java.io.File;

public final class SegmentDebugApp {
    private SegmentDebugApp() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: mvn exec:java -Dexec.mainClass=com.pdfreplace.SegmentDebugApp "
                    + "-Dexec.args=\"input.pdf search\"");
            System.exit(2);
        }

        DeterministicPdfReplacer.debugSegments(new File(args[0]), args[1], 8, true);
    }
}
