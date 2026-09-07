package com.javaclaw.server.role;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleFileFormat;
import com.javaclaw.api.AgentRoleSpec;
import com.javaclaw.api.CapabilityNarrowing;
import com.javaclaw.api.ModelPreference;
import com.javaclaw.api.PermissionConstraint;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;

/** 严格校验明确交换模式的 TOML；扩展命名空间仅保留数据，绝不执行或授予权限。 */
public final class AgentRoleTomlCodec {
    /** 单个 UTF-8 文件的最大字节数。 */
    public static final int MAXIMUM_BYTES = 1_048_576;

    private static final Set<String> CORE_FIELDS =
            Set.of("name", "description", "developer_instructions", "model", "model_reasoning_effort");
    private static final Set<String> JAVACLAW_FIELDS = Set.of(
            "schema_version",
            "id",
            "revision",
            "permission_constraint",
            "capabilities",
            "skills",
            "provider_id",
            "provider_revision");

    /** 创建无外部状态的文件 codec。 */
    public AgentRoleTomlCodec() {}

    /**
     * 解析并验证内容，未经明确 Provider 映射的模型仅保留名称。
     *
     * @param content UTF-8 可编码内容
     * @param format 调用者明确选择的模式
     * @return 规范化内容及可预览定义
     */
    public ParsedRole parse(String content, AgentRoleFileFormat format) {
        Objects.requireNonNull(format, "format");
        String normalized = normalize(content);
        TomlParseResult parsed = Toml.parse(normalized);
        if (parsed.hasErrors()) {
            throw new IllegalArgumentException(
                    "Agent TOML 语法无效: " + parsed.errors().getFirst());
        }
        for (String key : parsed.keySet()) {
            boolean extension = format == AgentRoleFileFormat.JAVACLAW_LOSSLESS
                    && (key.equals("javaclaw") || key.equals("extensions"));
            if (!CORE_FIELDS.contains(key) && !extension) {
                throw new IllegalArgumentException("未知 Agent TOML 字段: " + key);
            }
        }
        TomlTable extra = parsed.getTable("javaclaw");
        validateJavaClaw(extra, format);
        Optional<String> model = Optional.ofNullable(parsed.getString("model")).map(String::strip);
        if (model.filter(String::isEmpty).isPresent()) {
            throw new IllegalArgumentException("模型标识不得为空");
        }
        Optional<ModelPreference> preference = provider(extra, model);
        AgentRoleSpec spec = new AgentRoleSpec(
                required(parsed, "name"),
                Optional.ofNullable(parsed.getString("description")).orElse(""),
                Optional.ofNullable(parsed.getString("developer_instructions")).orElse(""),
                preference,
                Optional.ofNullable(parsed.getString("model_reasoning_effort"))
                        .map(value -> ReasoningPreference.valueOf(value.toUpperCase(Locale.ROOT))),
                new CapabilityNarrowing(strings(extra, "capabilities"), strings(extra, "skills")),
                constraint(extra),
                extensions(parsed.getTable("extensions")));
        return new ParsedRole(
                spec,
                model.filter(value -> preference.isEmpty()),
                Optional.ofNullable(extra == null ? null : extra.getString("id")),
                digest(normalized));
    }

    /**
     * 以明确模式导出角色；可移植模式故意不携带 JavaClaw 权限收窄与扩展字段。
     *
     * @param role 精确角色版本
     * @param format 导出模式
     * @return LF 换行、可直接保存为 UTF-8 的 TOML
     */
    public String export(AgentRole role, AgentRoleFileFormat format) {
        Objects.requireNonNull(format, "format");
        AgentRoleSpec spec = role.spec();
        StringBuilder result = new StringBuilder();
        field(result, "name", spec.name());
        field(result, "description", spec.description());
        field(result, "developer_instructions", spec.developerInstructions());
        spec.model().ifPresent(value -> field(result, "model", value.provider().model()));
        spec.reasoning()
                .ifPresent(value ->
                        field(result, "model_reasoning_effort", value.name().toLowerCase(Locale.ROOT)));
        if (format == AgentRoleFileFormat.JAVACLAW_LOSSLESS) {
            result.append("\n[javaclaw]\nschema_version = 1\n");
            field(result, "id", role.id());
            result.append("revision = ").append(role.revision()).append('\n');
            field(result, "permission_constraint", spec.permissionConstraint().name());
            spec.narrowing().capabilities().ifPresent(values -> array(result, "capabilities", values));
            spec.narrowing().skills().ifPresent(values -> array(result, "skills", values));
            spec.model().ifPresent(value -> {
                field(result, "provider_id", value.provider().endpointId());
                result.append("provider_revision = ")
                        .append(value.provider().endpointRevision())
                        .append('\n');
            });
            new TreeMap<>(spec.extensions())
                    .forEach((namespace, body) -> result.append("\n[extensions.")
                            .append(namespace.matches("[A-Za-z0-9_-]+") ? namespace : quote(namespace))
                            .append("]\n")
                            .append(body.strip())
                            .append('\n'));
        }
        String content = normalize(result.toString());
        parse(content, format);
        return content;
    }

    /**
     * 规范化换行并计算字节限额，不使用替换字符掩盖非法 Unicode。
     *
     * @param content 文件内容
     * @return 以 LF 结尾的规范化文本
     */
    public static String normalize(String content) {
        try {
            int length = StandardCharsets.UTF_8
                    .newEncoder()
                    .encode(CharBuffer.wrap(content))
                    .remaining();
            if (length > MAXIMUM_BYTES) {
                throw new IllegalArgumentException("Agent TOML 超过 1 MiB 限制");
            }
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Agent TOML 必须是有效 UTF-8", failure);
        }
        String value = content.startsWith("\uFEFF") ? content.substring(1) : content;
        return value.replace("\r\n", "\n").replace('\r', '\n').strip() + "\n";
    }

    /**
     * 计算 UTF-8 内容摘要。
     *
     * @param content 已规范化内容
     * @return 小写 SHA-256
     */
    public static String digest(String content) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 不可用", failure);
        }
    }

    private static void validateJavaClaw(TomlTable extra, AgentRoleFileFormat format) {
        if (format == AgentRoleFileFormat.JAVACLAW_LOSSLESS && extra == null) {
            throw new IllegalArgumentException("完整格式必须提供 [javaclaw] schema_version");
        }
        if (extra == null) {
            return;
        }
        if (!Long.valueOf(1).equals(extra.getLong("schema_version"))) {
            throw new IllegalArgumentException("不支持的 JavaClaw Role schema_version");
        }
        for (String key : extra.keySet()) {
            if (!JAVACLAW_FIELDS.contains(key)) {
                throw new IllegalArgumentException("未知 javaclaw 字段: " + key);
            }
        }
        if (extra.contains("revision") && extra.getLong("revision") < 1) {
            throw new IllegalArgumentException("Role revision 必须为正数");
        }
    }

    private static Optional<ModelPreference> provider(TomlTable extra, Optional<String> model) {
        if (extra == null) {
            return Optional.empty();
        }
        boolean id = extra.contains("provider_id");
        boolean revision = extra.contains("provider_revision");
        if (id != revision || (id && model.isEmpty())) {
            throw new IllegalArgumentException("精确 Provider 映射必须同时提供模型、标识和版本");
        }
        return id
                ? Optional.of(new ModelPreference(new ProviderRef(
                        extra.getString("provider_id"), extra.getLong("provider_revision"), model.orElseThrow())))
                : Optional.empty();
    }

    private static PermissionConstraint constraint(TomlTable extra) {
        return Optional.ofNullable(extra == null ? null : extra.getString("permission_constraint"))
                .map(value -> PermissionConstraint.valueOf(value.toUpperCase(Locale.ROOT)))
                .orElse(PermissionConstraint.INHERIT);
    }

    private static Optional<Set<String>> strings(TomlTable table, String key) {
        TomlArray array = table == null ? null : table.getArray(key);
        if (array == null) {
            return Optional.empty();
        }
        Set<String> values = new LinkedHashSet<>();
        for (int index = 0; index < array.size(); index++) {
            values.add(array.getString(index));
        }
        return Optional.of(Set.copyOf(values));
    }

    private static Map<String, String> extensions(TomlTable table) {
        if (table == null) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String namespace : table.keySet()) {
            TomlTable values = table.getTable(List.of(namespace));
            if (values == null) {
                throw new IllegalArgumentException("扩展必须使用命名空间 table");
            }
            result.put(namespace, serialize(values));
        }
        return Map.copyOf(result);
    }

    private static String serialize(TomlTable table) {
        return table.keySet().stream()
                .sorted()
                .map(key -> {
                    requireSafeField(key);
                    return quote(key) + " = " + value(table.get(List.of(key))) + "\n";
                })
                .collect(Collectors.joining());
    }

    private static String value(Object value) {
        if (value instanceof String text) {
            requireSafeValue(text);
            return quote(text);
        }
        if (value instanceof TomlTable table) {
            return "{ " + serialize(table).strip().replace("\n", ", ") + " }";
        }
        if (value instanceof TomlArray array) {
            List<String> values = new ArrayList<>();
            for (int index = 0; index < array.size(); index++) {
                values.add(value(array.get(index)));
            }
            return "[" + String.join(", ", values) + "]";
        }
        if (value instanceof Double number && !Double.isFinite(number)) {
            return Double.isNaN(number) ? "nan" : (number > 0 ? "inf" : "-inf");
        }
        if (value instanceof java.time.LocalTime time) {
            return java.time.format.DateTimeFormatter.ISO_LOCAL_TIME.format(time);
        }
        if (value instanceof java.time.LocalDateTime dateTime) {
            return java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(dateTime);
        }
        if (value instanceof java.time.OffsetDateTime dateTime) {
            return java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(dateTime);
        }
        return value.toString();
    }

    private static void requireSafeField(String field) {
        String key = field.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (key.matches(".*(secret|credential|password|apikey|token|authorization|permission|grant).*")) {
            throw new IllegalArgumentException("Role 文件不允许敏感配置或权限授权字段");
        }
    }

    private static void requireSafeValue(String value) {
        if (value.startsWith("/")
                || value.startsWith("\\\\")
                || value.matches("^[A-Za-z]:[/\\\\].*")
                || value.startsWith("sk-")
                || value.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Role 扩展不允许绝对执行路径或凭据");
        }
    }

    private static String required(TomlTable table, String key) {
        String value = table.getString(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Agent TOML 缺少字段: " + key);
        }
        return value;
    }

    private static void field(StringBuilder target, String key, String value) {
        target.append(key).append(" = ").append(quote(value)).append('\n');
    }

    private static void array(StringBuilder target, String key, Set<String> values) {
        target.append(key)
                .append(" = [")
                .append(values.stream().sorted().map(AgentRoleTomlCodec::quote).collect(Collectors.joining(", ")))
                .append("]\n");
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        value.codePoints().forEach(code -> {
            switch (code) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (code < 0x20 || code == 0x7f) {
                        result.append(String.format("\\u%04x", code));
                    } else {
                        result.appendCodePoint(code);
                    }
                }
            }
        });
        return result.append('"').toString();
    }

    /**
     * 文件解析后的候选值，模型名称尚未解析时禁止提交运行。
     *
     * @param spec 候选 Role 定义
     * @param unresolvedModel 未映射的模型名称
     * @param declaredId 完整文件声明的原 Role 标识
     * @param contentDigest 规范化内容摘要
     */
    public record ParsedRole(
            AgentRoleSpec spec, Optional<String> unresolvedModel, Optional<String> declaredId, String contentDigest) {}
}
