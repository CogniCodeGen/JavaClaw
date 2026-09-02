package com.javaclaw.server.extension.thirdparty;

import java.nio.file.Path;
import java.util.Objects;

/** Bundle 内部路径的单一校验入口。 */
final class BundlePathNames {
    private BundlePathNames() {}

    static String requireRelative(String value, String name) {
        String supplied = Objects.requireNonNull(value, name);
        if (supplied.indexOf('\0') >= 0 || supplied.indexOf('\\') >= 0 || supplied.startsWith("/")) {
            throw new IllegalArgumentException(name + " must use a safe relative POSIX path");
        }
        Path path = Path.of(supplied).normalize();
        if (path.isAbsolute() || path.startsWith("..") || path.toString().equals(".")) {
            throw new IllegalArgumentException(name + " escapes the bundle");
        }
        String normalized = path.toString().replace('\\', '/');
        if (!normalized.equals(supplied)) {
            throw new IllegalArgumentException(name + " must already be normalized");
        }
        return normalized;
    }
}
