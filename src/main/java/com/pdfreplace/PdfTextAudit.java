package com.pdfreplace;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;

public final class PdfTextAudit {
    private PdfTextAudit() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: mvn exec:java -Dexec.mainClass=com.pdfreplace.PdfTextAudit -Dexec.args=\"file.pdf\"");
            System.exit(2);
        }

        try (PDDocument document = PDDocument.load(new File(args[0]))) {
            PDFTextStripper stripper = new PDFTextStripper();
            System.out.println(stripper.getText(document));
        }
    }
}
