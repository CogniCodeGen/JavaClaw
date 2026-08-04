package com.javaclaw.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 对日志和诊断轨迹中的工具入参做保守脱敏。 */
public final class SensitiveDataRedactor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REDACTED = "<redacted>";

    private static final Pattern MAP_SECRET = Pattern.compile(
            "(?i)(password|passwd|secret|token|api[_ .-]?key|authorization|cookie)\\s*=\\s*([^,}\\n]*)");
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)(\\\"(?:password|passwd|secret|token|api[_ .-]?key|authorization|cookie)\\\"\\s*:\\s*\\\")([^\\\"]*)(\\\")");
    private static final Pattern LABELED_SECRET = Pattern.compile(
            "(?i)[\\\"']?(?:password|passwd|pwd|passcode|secret|token|api[_ .-]?key|authorization|cookie"
                    + "|密码|口令|令牌|密钥|验证码|会话)[\\\"']?\\s*[:：=]\\s*"
                    + "(?:[\\\"']([^\\\"'\\r\\n]{4,})[\\\"']|([^\\s,，;；\\r\\n]{4,}))");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----");
    private static final Pattern JWT = Pattern.compile(
            "(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(?![A-Za-z0-9_-])");
    private static final Pattern PROVIDER_TOKEN = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_-])(?:sk|ghp|github_pat|xox[baprs])[-_][A-Za-z0-9_-]{12,}(?![A-Za-z0-9_-])");
    private static final Pattern URL_CREDENTIAL = Pattern.compile(
            "(?i)https?://[^/@\\s:]+:[^/@\\s]+@");
    private static final Pattern COMPARATIVE_SECRET = Pattern.compile(
            "(?i)(?:password|passwd|pwd|passcode|secret|token|api[_ .-]?key|authorization|cookie"
                    + "|密码|口令|令牌|密钥|验证码|会话)\\s*(?:不是|不再是|从|由|改为|改成)\\s*"
                    + "[^\\s,，;；\\r\\n]{4,}");
    private static final Pattern BEARER_SECRET = Pattern.compile(
            "(?i)(?:authorization\\s*[:=]\\s*)?bearer\\s+[A-Za-z0-9._~+/-]{8,}={0,2}");
    private static final Pattern URL_QUERY_SECRET = Pattern.compile(
            "(?i)[?&](?:access[_-]?token|auth|authorization|api[_-]?key|key|password|passwd|secret|token)="
                    + "[^&#\\s]{4,}");

    private SensitiveDataRedactor() {}

    /**
     * 脱敏工具调用入参。Map/JSON 会按字段名递归处理；无法解析的 MCP 配置 JSON 整段隐藏。
     */
    public static String redactToolInput(String toolName, Object input) {
        if (input == null) return "";
        if (input instanceof Map<?, ?> map) {
            return redactMap(toolName, map).toString();
        }
        return redactFallback(String.valueOf(input));
    }

    /** 对 JSON 文本按敏感字段名递归脱敏；无法解析时做正则兜底。 */
    public static String redactJson(String json) {
        if (json == null || json.isBlank()) return json == null ? "" : json;
        try {
            JsonNode node = MAPPER.readTree(json);
            redactJsonNode(node);
            return MAPPER.writeValueAsString(node);
        } catch (Exception ignored) {
            return redactFallback(json);
        }
    }

    /**
     * 保守识别不应写入普通文件、技能、知识库或长期记忆的真实凭据。
     * 只返回布尔值，调用方不得把命中的秘密复述到错误信息或日志中。
     */
    public static boolean containsLikelyCredential(String text) {
        if (text == null || text.isBlank()) return false;
        if (PRIVATE_KEY.matcher(text).find() || JWT.matcher(text).find()
                || PROVIDER_TOKEN.matcher(text).find() || URL_CREDENTIAL.matcher(text).find()
                || COMPARATIVE_SECRET.matcher(text).find() || BEARER_SECRET.matcher(text).find()
                || URL_QUERY_SECRET.matcher(text).find()) {
            return true;
        }
        Matcher matcher = LABELED_SECRET.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (!isPlaceholder(value)) return true;
        }
        return false;
    }

    /** 可直接返回给模型的固定错误，不包含命中值。 */
    public static String credentialStorageDeniedReason() {
        return "检测到疑似凭据，已拒绝写入普通文件或知识数据；请改用专用站点凭据工具";
    }

    /**
     * 对任意日志/轨迹文本做保守处理。命中凭据时整段隐藏，避免复杂格式的秘密被局部正则漏出。
     */
    public static String redactText(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        if (containsLikelyCredential(text)) return "<敏感内容已隐藏>";
        return redactFallback(text);
    }

    private static boolean isPlaceholder(String value) {
        if (value == null) return true;
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return true;
        String compact = normalized.replaceAll("[\\s_-]", "");
        return normalized.contains("<redacted>") || normalized.contains("placeholder")
                || normalized.contains("example") || normalized.contains("示例")
                || normalized.contains("config.") || normalized.contains("getpassword")
                || normalized.contains("system.getenv") || normalized.startsWith("${")
                || normalized.startsWith("{{") || normalized.startsWith("%")
                || normalized.matches("[x*•]{3,}")
                || compact.equals("redacted") || compact.equals("masked")
                || compact.equals("changeme") || compact.equals("yourpassword")
                || compact.equals("yourtoken") || compact.equals("yourapikey")
                || compact.equals("password") || compact.equals("token")
                || compact.equals("secret") || compact.equals("string")
                || compact.equals("null") || compact.equals("none")
                || compact.equals("未设置") || compact.equals("用户密码") || compact.equals("密码值");
    }

    private static Map<String, Object> redactMap(String toolName, Map<?, ?> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (isSensitiveKey(key)) {
                result.put(key, REDACTED);
            } else if ("config_json".equalsIgnoreCase(key)
                    && toolName != null && toolName.startsWith("mcp_server_")) {
                // MCP 的秘密可能放在任意 env 名、header 名、URL 查询参数或 args 中，
                // 仅按常见字段名递归脱敏仍可能漏出。日志不需要配置正文，整段隐藏最稳妥。
                result.put(key, "<redacted-config-json>");
            } else {
                result.put(key, redactValue(toolName, value));
            }
        }
        return result;
    }

    private static Object redactValue(String toolName, Object value) {
        if (value instanceof Map<?, ?> map) return redactMap(toolName, map);
        if (value instanceof Iterable<?> iterable) {
            List<Object> values = new ArrayList<>();
            for (Object item : iterable) values.add(redactValue(toolName, item));
            return values;
        }
        if (value instanceof String text) return redactFallback(text);
        return value;
    }

    private static void redactJsonNode(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> fields = new ArrayList<>();
            object.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                if (isSensitiveKey(field)) {
                    object.put(field, REDACTED);
                } else {
                    redactJsonNode(object.get(field));
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(SensitiveDataRedactor::redactJsonNode);
        }
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("apikey")
                || normalized.contains("authorization")
                || normalized.equals("cookie")
                || normalized.equals("cookies");
    }

    private static String redactFallback(String raw) {
        if (raw == null || raw.isEmpty()) return raw == null ? "" : raw;
        Matcher jsonMatcher = JSON_SECRET.matcher(raw);
        String jsonRedacted = jsonMatcher.replaceAll("$1" + REDACTED + "$3");
        Matcher mapMatcher = MAP_SECRET.matcher(jsonRedacted);
        return mapMatcher.replaceAll("$1=" + REDACTED);
    }
}
