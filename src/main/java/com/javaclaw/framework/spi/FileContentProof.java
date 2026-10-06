package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Bounded content proof made from text independently observed by the trusted file adapter. */
public final class FileContentProof {
    private static final String FORMAT = "stripped-utf8-sha256-v1";

    private FileContentProof() { }

    public static Map<String, String> metadata(String observedContent) {
        // TaskCriterionV3 and receipt subjects strip their outer whitespace as well.
        String content = observedContent.strip();
        return Map.of("fileContentFormat", FORMAT,
                "fileContentSha256", digest(content),
                "fileContentCharacters", Integer.toString(content.length()));
    }

    public static boolean matches(String requiredSubject, JsonNode metadata) {
        if (requiredSubject == null || requiredSubject.isBlank() || metadata == null
                || !FORMAT.equals(metadata.path("fileContentFormat").asText(""))) return false;
        String expected = requiredSubject.strip();
        return Integer.toString(expected.length()).equals(
                        metadata.path("fileContentCharacters").asText(""))
                && digest(expected).equals(metadata.path("fileContentSha256").asText(""));
    }

    private static String digest(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
