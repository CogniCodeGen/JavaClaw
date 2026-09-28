package com.javaclaw.memory.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Stable text and content versions for deferred memory evidence. */
public final class MemoryContextBody {
    private MemoryContextBody() { }

    public static String fact(Fact fact) {
        return "Memory fact" + (fact.section == null ? "" : " (" + fact.section + ")")
                + ": " + Objects.requireNonNullElse(fact.text, "");
    }

    public static String episode(Episode episode) {
        return "Prior conversation evidence " + episode.evidenceKey() + "\nUser: "
                + Objects.requireNonNullElse(episode.userInput, "") + "\nAssistant: "
                + Objects.requireNonNullElse(episode.assistantReply, "") + "\nTool evidence: "
                + Objects.requireNonNullElse(episode.toolTraceJson, "");
    }

    public static String knowledge(KnowledgeChunk chunk) {
        return "Knowledge source: " + chunk.docName + "\n" + chunk.content;
    }

    public static String digest(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
