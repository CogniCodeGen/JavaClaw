package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Conservative effect identity that ignores incidental arguments such as email body or timeout. */
final class ToolEffectKey {
    private ToolEffectKey() { }

    static String create(String tool, JsonNode arguments, String fingerprint) {
        String target = switch (tool) {
            case "email_send", "email_send_with_cc" -> field(arguments, "to") + "|" + field(arguments, "subject");
            case "schedule_create" -> field(arguments, "name");
            case "schedule_run_now", "schedule_disable", "schedule_delete" -> field(arguments, "id");
            case "sys_file_write", "sys_file_delete", "sys_file_mkdir" -> field(arguments, "path");
            case "sys_file_copy", "sys_file_move" -> field(arguments, "target");
            case "desktop_session_launch_application" -> field(arguments, "application");
            case "desktop_session_open" -> field(arguments, "targetId");
            case "desktop_session_click" -> field(arguments, "sessionId") + "|"
                    + field(arguments, "observationId") + "|" + field(arguments, "elementId")
                    + "|" + field(arguments, "x") + "|" + field(arguments, "y")
                    + "|" + field(arguments, "button") + "|" + field(arguments, "clicks");
            case "web_click", "web_dblclick" -> field(arguments, "target");
            case "web_navigate" -> com.javaclaw.framework.spi.BrowserReceiptProof.canonicalUrl(
                    field(arguments, "url"));
            case "cmd_execute" -> field(arguments, "command") + "|" + field(arguments, "workDir");
            case "cmd_session_exec" -> field(arguments, "sessionId") + "|" + field(arguments, "command");
            default -> "";
        };
        if (target.isBlank()) return fingerprint;
        try {
            String family = tool.equals("email_send_with_cc") ? "email_send" : tool;
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((family + "\n" + target).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String field(JsonNode arguments, String name) {
        JsonNode value = arguments == null ? null : arguments.get(name);
        return value == null || value.isNull() ? "" : value.asText("").strip();
    }
}
