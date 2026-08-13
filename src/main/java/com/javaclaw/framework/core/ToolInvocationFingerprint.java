package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Canonical fingerprint shared by approval creation and exact approval continuation. */
public final class ToolInvocationFingerprint {
    private ToolInvocationFingerprint() { }

    public static String create(String toolName, JsonNode arguments) {
        try {
            StringBuilder canonical = new StringBuilder();
            appendCanonical(Objects.requireNonNull(arguments, "arguments"), canonical);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (Objects.requireNonNull(toolName, "toolName") + "\n" + canonical)
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void appendCanonical(JsonNode value, StringBuilder target) {
        if (value == null || value.isNull()) {
            target.append("null");
        } else if (value.isObject()) {
            target.append('{');
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            for (int index = 0; index < names.size(); index++) {
                if (index > 0) target.append(',');
                String name = names.get(index);
                target.append(JsonNodeFactory.instance.textNode(name)).append(':');
                appendCanonical(value.get(name), target);
            }
            target.append('}');
        } else if (value.isArray()) {
            target.append('[');
            for (int index = 0; index < value.size(); index++) {
                if (index > 0) target.append(',');
                appendCanonical(value.get(index), target);
            }
            target.append(']');
        } else {
            target.append(value);
        }
    }
}
