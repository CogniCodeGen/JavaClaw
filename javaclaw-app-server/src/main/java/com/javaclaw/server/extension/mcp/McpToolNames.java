package com.javaclaw.server.extension.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable collision-safe MCP names accepted by model-provider tool schemas. */
final class McpToolNames {
    private static final int MAX_NAME_LENGTH = 200;

    private McpToolNames() {}

    static Map<String, String> allocate(String serverId, List<String> remoteNames) {
        String server = component(serverId, 60);
        HashMap<String, Integer> counts = new HashMap<>();
        remoteNames.forEach(remote -> counts.merge(base(server, remote), 1, Integer::sum));
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (String remote : remoteNames) {
            String base = base(server, remote);
            boolean collision = counts.getOrDefault(base, 0) > 1;
            String allocated = collision || base.length() > MAX_NAME_LENGTH ? suffix(base, remote) : base;
            String previous = result.putIfAbsent(remote, allocated);
            if (previous != null && !previous.equals(allocated)) {
                throw new IllegalArgumentException("duplicate MCP remote tool name");
            }
        }
        return Map.copyOf(result);
    }

    static String synthetic(String serverId, String operation) {
        String value = "mcp__" + component(serverId, 80) + "__" + operation;
        return value.length() <= MAX_NAME_LENGTH ? value : suffix(value, serverId + operation);
    }

    private static String base(String server, String remote) {
        return "mcp__" + server + "__tool__" + component(remote, 120);
    }

    private static String component(String value, int maximum) {
        String result = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_-]", "_");
        if (result.isEmpty()) {
            result = "unknown";
        }
        if (result.length() <= maximum) {
            return result;
        }
        return result.substring(0, Math.max(1, maximum - 10)) + "__" + hash(value);
    }

    private static String suffix(String base, String original) {
        String suffix = "__" + hash(original);
        int keep = Math.max(1, MAX_NAME_LENGTH - suffix.length());
        return (base.length() > keep ? base.substring(0, keep) : base) + suffix;
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
