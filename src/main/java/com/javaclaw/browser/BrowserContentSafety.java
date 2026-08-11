package com.javaclaw.browser;

import com.javaclaw.util.SensitiveDataRedactor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Applies content-level safety checks before a project file crosses the browser boundary. */
final class BrowserContentSafety {

    private static final long MAX_INSPECTION_BYTES = 4L * 1024 * 1024;

    private BrowserContentSafety() {}

    static boolean containsLikelyCredential(Path file) {
        try {
            return Files.isRegularFile(file)
                    && Files.size(file) <= MAX_INSPECTION_BYTES
                    && SensitiveDataRedactor.containsLikelyCredential(Files.readString(file));
        } catch (IOException | RuntimeException unreadable) {
            // The upload operation performs its own path/read validation and will report that
            // failure.
            return false;
        }
    }
}
