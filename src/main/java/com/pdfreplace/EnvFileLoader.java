package com.pdfreplace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class EnvFileLoader {
    private EnvFileLoader() {
    }

    static void loadIfPresent(Path envPath) {
        if (envPath == null || !Files.isRegularFile(envPath)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(envPath);
            for (String rawLine : lines) {
                String line = rawLine == null ? "" : rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String key = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                if (!key.isEmpty() && System.getenv(key) == null && System.getProperty(key) == null) {
                    System.setProperty(key, value);
                }
            }
        } catch (IOException ignored) {
            // Best effort: app can still run from normal environment variables.
        }
    }
}
