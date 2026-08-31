package com.javaclaw.server.extension;

import java.net.IDN;
import java.nio.file.Path;
import java.util.Objects;

final class PluginValidation {
    private PluginValidation() {}

    static String id(String value, String name) {
        value = Objects.requireNonNull(value, name).strip();
        if (!value.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*") || value.length() > 120) {
            throw new IllegalArgumentException(name + " is invalid: " + value);
        }
        return value;
    }

    static String version(String value) {
        value = Objects.requireNonNull(value, "version").strip();
        if (!value.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?") || value.length() > 100) {
            throw new IllegalArgumentException("plugin version is not SemVer-like: " + value);
        }
        return value;
    }

    static String relativePath(String value, String name) {
        value = Objects.requireNonNull(value, name).strip();
        if (value.isEmpty() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        Path path = Path.of(value);
        if (path.isAbsolute() || path.normalize().startsWith("..")) {
            throw new IllegalArgumentException(name + " must remain inside the plugin bundle");
        }
        return path.normalize().toString();
    }

    static String host(String value) {
        value = Objects.requireNonNull(value, "host").strip().toLowerCase(java.util.Locale.ROOT);
        if (value.isEmpty()
                || value.contains("://")
                || value.contains("/")
                || value.contains("@")
                || value.contains("\\")) {
            throw new IllegalArgumentException("network allowlist entry must be a hostname");
        }
        int separator = value.lastIndexOf(':');
        boolean hasPort = separator > 0 && value.indexOf(':') == separator;
        String port = hasPort ? value.substring(separator + 1) : null;
        String host = hasPort ? value.substring(0, separator) : value;
        boolean wildcard = host.startsWith("*.");
        if (wildcard) {
            host = host.substring(2);
        }
        if (port != null) {
            try {
                int parsed = Integer.parseInt(port);
                if (parsed < 1 || parsed > 65535) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("network allowlist port is invalid", failure);
            }
        }
        String ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
        if (ascii.length() > 253) {
            throw new IllegalArgumentException("hostname is too long");
        }
        return (wildcard ? "*." : "") + ascii + (port == null ? "" : ":" + port);
    }

    static String platform(String value) {
        value = Objects.requireNonNull(value, "platform").strip().toLowerCase(java.util.Locale.ROOT);
        if (!value.matches("(?:windows|macos|linux)(?:-(?:x64|arm64))?")) {
            throw new IllegalArgumentException("unsupported plugin platform: " + value);
        }
        return value;
    }
}
