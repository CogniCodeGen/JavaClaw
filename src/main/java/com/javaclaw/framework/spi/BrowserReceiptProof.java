package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.util.SensitiveDataRedactor;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.List;

/** 浏览器专用 URL 摘要及有界正文证明，不改变其它能力的目标或内容匹配。 */
public final class BrowserReceiptProof {
    private static final String URL_FORMAT = "canonical-http-url-sha256-v1";
    private static final String INPUT_URL_FORMAT = "host-input-page-http-url-sha256-v1";
    private static final String CONTENT_FORMAT = "visible-text-nfc-whitespace-v1";
    private static final int PARTS = 8;
    private static final int PART_LENGTH = 512;

    private BrowserReceiptProof() { }

    /** 规范化真实 URL，保留查询参数及其顺序；无协议时与导航工具一致补全 HTTPS。 */
    public static String canonicalUrl(String raw) {
        if (raw == null || raw.isBlank() || SensitiveDataRedactor.containsLikelyCredential(raw)) return "";
        try {
            String input = raw.strip();
            if (!input.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*$")) input = "https://" + input;
            URI uri = URI.create(input).normalize();
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("https") && !scheme.equals("http"))
                    || uri.getHost() == null || uri.getRawUserInfo() != null) return "";
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if ((scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80)) port = -1;
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            String value = scheme + "://" + host + (port < 0 ? "" : ":" + port) + path
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                    + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
            return URI.create(normalizeEscapes(URI.create(value).toASCIIString())).normalize().toASCIIString();
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    /** 返回规范 URL 的摘要；不持久化查询正文或凭据。 */
    public static String urlDigest(String raw) {
        String canonical = canonicalUrl(raw);
        return canonical.isBlank() ? "" : digest(canonical);
    }

    /** 为宿主读取的真实 URL 生成查询证明，展示 URL 仍可独立脱敏。 */
    public static Map<String, String> urlMetadata(String observedUrl) {
        String hash = urlDigest(observedUrl);
        // 无法证明时保留格式标记，禁止匹配器退回已删除查询参数的展示 URL。
        return Map.of("browserUrlFormat", URL_FORMAT, "browserUrlSha256", hash);
    }

    /** Extra proof for a host-captured pre-dispatch page; the returned-page proof stays separate. */
    public static Map<String, String> inputUrlMetadata(String inputUrl) {
        String canonical = canonicalUrl(inputUrl);
        if (canonical.isBlank()) return Map.of();
        String host = URI.create(canonical).getHost();
        if (host == null || host.isBlank() || host.length() > 512) return Map.of();
        return Map.of("browserInputUrlFormat", INPUT_URL_FORMAT,
                "browserInputUrlSha256", digest(canonical), "browserInputHost", host);
    }

    /** 对宿主实际读到的正文生成证明；疑似秘密不进入普通回执。 */
    public static Map<String, String> observationMetadata(String observedUrl, String observedText) {
        Map<String, String> result = new LinkedHashMap<>(urlMetadata(observedUrl));
        if (result.get("browserUrlSha256").isBlank() || observedText == null
                || SensitiveDataRedactor.containsLikelyCredential(observedText)) return Map.copyOf(result);
        String text = normalizeText(observedText);
        if (text.isBlank()) return Map.copyOf(result);
        result.put("browserContentFormat", CONTENT_FORMAT);
        result.put("browserContentScope", "visible-body-or-element-text");
        int cursor = 0;
        for (int part = 0; part < PARTS && cursor < text.length(); part++) {
            int end = Math.min(cursor + PART_LENGTH, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            result.put("browserContent" + part, text.substring(cursor, end));
            cursor = end;
        }
        return Map.copyOf(result);
    }

    /** 是否包含新版浏览器 URL 证明；有证明时不能回退到丢失查询参数的展示目标。 */
    public static boolean hasUrlProof(JsonNode metadata) {
        return metadata != null && metadata.has("browserUrlFormat");
    }

    /** 精确 URL（含查询参数）必须与可信宿主摘要一致。 */
    public static boolean urlMatches(String expected, JsonNode metadata) {
        if (metadata == null || !URL_FORMAT.equals(metadata.path("browserUrlFormat").asText())) return false;
        String hash = urlDigest(expected);
        return !hash.isBlank() && hash.equals(metadata.path("browserUrlSha256").asText());
    }

    /** INPUT_PAGE never falls back to the returned URL or to legacy evidence without input proof. */
    public static boolean inputUrlMatches(String expected, JsonNode metadata) {
        if (expected == null || expected.isBlank() || metadata == null
                || !INPUT_URL_FORMAT.equals(metadata.path("browserInputUrlFormat").asText())
                || !metadata.path("browserInputUrlSha256").isTextual()
                || !metadata.path("browserInputUrlSha256").asText().matches("[0-9a-f]{64}")
                || !metadata.path("browserInputHost").isTextual()) return false;
        String host = metadata.path("browserInputHost").textValue();
        String canonicalHostUrl = canonicalUrl(host);
        if (host.isBlank() || host.length() > 512 || canonicalHostUrl.isBlank()
                || !host.equals(URI.create(canonicalHostUrl).getHost())) return false;
        if (!expected.contains(":") && !expected.contains("/")
                && !expected.contains("?") && !expected.contains("#")) {
            return expected.equalsIgnoreCase(host);
        }
        String hash = urlDigest(expected);
        return !hash.isBlank() && hash.equals(metadata.path("browserInputUrlSha256").asText());
    }

    /** 只证明一段字面文字出现在真实正文内，不推断页面已完成任何抽象业务目标。 */
    public static boolean contentMatches(String required, JsonNode metadata) {
        if (required == null || required.isBlank() || metadata == null
                || !CONTENT_FORMAT.equals(metadata.path("browserContentFormat").asText())
                || !metadata.path("browserUrlSha256").asText().matches("[0-9a-f]{64}")
                || !metadata.path("browserContentScope").asText().equals("visible-body-or-element-text")) return false;
        String expected = normalizeText(required);
        if (expected.isBlank() || expected.length() > 128) return false;
        StringBuilder observed = new StringBuilder();
        for (int part = 0; part < PARTS; part++) {
            JsonNode value = metadata.get("browserContent" + part);
            if (value == null) break;
            if (!value.isTextual() || value.textValue().length() > PART_LENGTH) return false;
            observed.append(value.textValue());
        }
        return observed.toString().contains(expected);
    }

    /** 多个独立字面片段必须同时出现在同一个真实正文证明中，不拼接或推断语义。 */
    public static boolean contentMatchesAll(List<String> required, JsonNode metadata) {
        return required != null && !required.isEmpty() && required.size() <= 8
                && required.stream().allMatch(value -> value != null && !value.isBlank()
                    && value.length() <= 128)
                && required.stream().mapToInt(String::length).sum() <= 256
                && required.stream().allMatch(value -> contentMatches(value, metadata));
    }

    private static String normalizeText(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("(?U)\\s+", " ").strip();
    }

    private static String normalizeEscapes(String value) {
        StringBuilder normalized = new StringBuilder();
        for (int at = 0; at < value.length(); at++) {
            char current = value.charAt(at);
            if (current == '%' && at + 2 < value.length()) {
                int decoded = Integer.parseInt(value.substring(at + 1, at + 3), 16);
                if ((decoded >= 'a' && decoded <= 'z') || (decoded >= 'A' && decoded <= 'Z')
                        || (decoded >= '0' && decoded <= '9') || "-._~".indexOf(decoded) >= 0) {
                    normalized.append((char) decoded);
                } else normalized.append('%').append(value.substring(at + 1, at + 3).toUpperCase(Locale.ROOT));
                at += 2;
            } else normalized.append(current);
        }
        return normalized.toString();
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }
}
