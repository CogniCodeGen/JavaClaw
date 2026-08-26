package com.javaclaw.infrastructure.chat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Deterministic identity for rows created before chat_messages.message_id existed. */
final class LegacyChatMessageIds {
    private LegacyChatMessageIds() { }

    static String derive(
            String workspaceId,
            String sessionId,
            int position,
            String role,
            String content,
            String timestamp) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, workspaceId);
            update(digest, sessionId);
            update(digest, Integer.toString(position));
            update(digest, role);
            update(digest, content);
            update(digest, timestamp);
            return "legacy-" + HexFormat.of().formatHex(digest.digest());
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0xff);
    }
}
