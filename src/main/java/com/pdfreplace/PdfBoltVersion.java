package com.pdfreplace;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Application version from {@code pdfbolt-version.properties} (Maven-filtered from pom.xml).
 */
public final class PdfBoltVersion {
    private static final String VERSION = loadVersion();

    private PdfBoltVersion() {
    }

    public static String get() {
        return VERSION;
    }

    private static String loadVersion() {
        try (InputStream in = PdfBoltVersion.class.getResourceAsStream("/pdfbolt-version.properties")) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                String version = properties.getProperty("version");
                if (version != null && !version.isBlank() && !version.contains("@")) {
                    return version.trim();
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        Package pkg = PdfBoltVersion.class.getPackage();
        if (pkg != null && pkg.getImplementationVersion() != null && !pkg.getImplementationVersion().isBlank()) {
            return pkg.getImplementationVersion();
        }
        return "0.1.0";
    }
}
