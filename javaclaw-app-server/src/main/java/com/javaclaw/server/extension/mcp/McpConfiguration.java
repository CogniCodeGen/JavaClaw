package com.javaclaw.server.extension.mcp;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.server.extension.McpRepository;

/**
 * Strict, secret-free MCP configuration parsed from the existing config_json column.
 *
 * @param id 资源或声明的稳定标识
 * @param pluginId 所属插件标识；独立 MCP 配置可为空
 * @param name 展示名称
 * @param revision 持久修订号，用于乐观锁和缓存失效
 * @param enabled 是否允许新调用使用该资源；禁用不删除历史
 * @param transport 非空 STDIO 或 HTTP 传输类型
 * @param endpoint HTTP 模式的 HTTPS URL；STDIO 必须为 null
 * @param processId 插件所属 STDIO 进程贡献标识；HTTP 必须为 null
 * @param networkAllowlist HTTP 必须提供的 Broker allowlist；STDIO 必须为空
 * @param authentication 认证元数据；null 使用 NONE，绝不包含凭据值
 * @param timeout 调用时长预算；null 使用 30 秒，必须为正且不超过 10 分钟
 * @param outputLimitBytes 输出字节上限，范围 1 到 16 MiB
 * @param workspaceId 可选工作区绑定；私网 OAuth 必须明确绑定，null 表示只使用默认公开网络
 */
public record McpConfiguration(
        String id,
        String pluginId,
        String name,
        long revision,
        boolean enabled,
        Transport transport,
        URI endpoint,
        String processId,
        Set<String> networkAllowlist,
        Authentication authentication,
        Duration timeout,
        long outputLimitBytes,
        String workspaceId) {
    /** 受支持的 MCP 传输类型：沙箱 stdio 或 Broker HTTPS。 */
    public enum Transport {
        STDIO,
        HTTP
    }

    /** 认证方式白名单；配置只保存引用，敏感值存入 SecretStore。 */
    public enum AuthenticationType {
        NONE,
        BEARER,
        API_KEY,
        OAUTH
    }

    /**
     * MCP 认证元数据，不含访问 token、刷新 token 或 client secret。
     *
     * @param type 认证类型；null 使用 NONE
     * @param credentialName 静态凭据引用名；NONE 可为 null，静态认证必须提供
     * @param headerName API Key 头名称，仅允许限定格式；非 API Key 模式可为 null
     * @param clientId OAuth 客户端标识；未预注册时可为 null
     * @param scopes 最小申请 scope 集合；null 归一为空集合
     */
    public record Authentication(
            AuthenticationType type, String credentialName, String headerName, String clientId, Set<String> scopes) {
        /** 归一可选字段并校验认证方式组合；拒绝 NONE 夹带认证参数、非法 API Key 头或 OAuth 覆盖头。 */
        public Authentication {
            type = type == null ? AuthenticationType.NONE : type;
            credentialName = blankToNull(credentialName);
            headerName = blankToNull(headerName);
            clientId = blankToNull(clientId);
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
            if (type == AuthenticationType.NONE
                    && (credentialName != null || headerName != null || clientId != null || !scopes.isEmpty())) {
                throw new IllegalArgumentException("auth metadata is invalid for type none");
            }
            if ((type == AuthenticationType.BEARER || type == AuthenticationType.API_KEY) && credentialName == null) {
                throw new IllegalArgumentException("static MCP auth requires credentialName");
            }
            if (type == AuthenticationType.API_KEY
                    && (headerName == null || !headerName.matches("(?i)(?:x-api-key|api-key)(?:-[A-Za-z0-9_-]+)?"))) {
                throw new IllegalArgumentException("MCP API Key header is invalid");
            }
            if (type == AuthenticationType.OAUTH && headerName != null) {
                throw new IllegalArgumentException("MCP OAuth cannot configure an API key header");
            }
        }
    }

    /** 校验两种传输的互斥字段与预算；STDIO 必须无网络无认证，HTTP 必须独立 HTTPS 且提供 allowlist。 */
    public McpConfiguration {
        id = required(id, "id", 160);
        pluginId = blankToNull(pluginId);
        workspaceId = blankToNull(workspaceId);
        name = required(name, "name", 500);
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        transport = Objects.requireNonNull(transport, "transport");
        processId = blankToNull(processId);
        networkAllowlist = networkAllowlist == null ? Set.of() : Set.copyOf(networkAllowlist);
        authentication = authentication == null
                ? new Authentication(AuthenticationType.NONE, null, null, null, Set.of())
                : authentication;
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("MCP timeout must be between 1ns and 10m");
        }
        if (outputLimitBytes < 1 || outputLimitBytes > McpProtocol.MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("MCP output limit must be between 1B and 16MiB");
        }
        if (transport == Transport.STDIO) {
            if (pluginId == null
                    || processId == null
                    || endpoint != null
                    || !networkAllowlist.isEmpty()
                    || authentication.type() != AuthenticationType.NONE) {
                throw new IllegalArgumentException("stdio MCP must be a plugin process without raw network or auth");
            }
        } else {
            if (pluginId != null
                    || processId != null
                    || endpoint == null
                    || !"https".equalsIgnoreCase(endpoint.getScheme())) {
                throw new IllegalArgumentException("HTTP MCP requires a standalone HTTPS URL");
            }
            if (networkAllowlist.isEmpty()) {
                throw new IllegalArgumentException("HTTP MCP requires a network allowlist");
            }
        }
    }

    /** 从既有 config_json 解析严格元数据；拒绝未知配置字段和不合法传输/认证组合，不修改 Schema。 */
    public static McpConfiguration parse(McpRepository.McpRecord record, ObjectMapper json) {
        Objects.requireNonNull(record, "record");
        try {
            JsonNode config = json.readTree(record.configJson());
            if (config == null || !config.isObject()) {
                throw new IllegalArgumentException("MCP config must be an object");
            }
            String transportText = requiredText(config, "transport").toLowerCase(Locale.ROOT);
            Transport transport =
                    switch (transportText) {
                        case "stdio" -> Transport.STDIO;
                        case "http" -> Transport.HTTP;
                        default -> throw new IllegalArgumentException("unsupported MCP transport");
                    };
            Set<String> allowed = transport == Transport.STDIO
                    ? Set.of("transport", "processId", "timeoutMillis", "outputLimitBytes")
                    : Set.of(
                            "transport",
                            "url",
                            "networkAllowlist",
                            "auth",
                            "timeoutMillis",
                            "outputLimitBytes",
                            "workspaceId");
            rejectUnknown(config, allowed, "config");
            Set<String> hosts = strings(config.path("networkAllowlist"), "networkAllowlist", 128);
            Authentication auth = parseAuth(config.get("auth"));
            URI endpoint = config.path("url").isTextual()
                    ? URI.create(config.path("url").asText()).normalize()
                    : null;
            return new McpConfiguration(
                    record.id(),
                    record.pluginId(),
                    record.name(),
                    record.revision(),
                    record.enabled(),
                    transport,
                    endpoint,
                    textOrNull(config.get("processId")),
                    hosts,
                    auth,
                    Duration.ofMillis(config.path("timeoutMillis").asLong(30_000)),
                    config.path("outputLimitBytes").asLong(4L * 1024L * 1024L),
                    textOrNull(config.get("workspaceId")));
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("MCP config is not valid JSON", failure);
        }
    }

    /** 以临时修订快照执行同一配置解析规则；仅验证，不持久化或连接外部 Server。 */
    public static void validateDraft(String id, String pluginId, String name, JsonNode config, ObjectMapper json) {
        try {
            parse(
                    new McpRepository.McpRecord(
                            id,
                            pluginId,
                            name,
                            json.writeValueAsString(config),
                            true,
                            "CONFIGURED",
                            1,
                            java.time.Instant.EPOCH),
                    json);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("MCP config cannot be encoded", failure);
        }
    }

    private static Authentication parseAuth(JsonNode value) {
        if (value == null || value.isNull()) {
            return new Authentication(AuthenticationType.NONE, null, null, null, Set.of());
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException("MCP auth must be an object");
        }
        rejectUnknown(value, Set.of("type", "credentialName", "headerName", "clientId", "scopes"), "config.auth");
        AuthenticationType type =
                switch (value.path("type").asText("none")) {
                    case "none" -> AuthenticationType.NONE;
                    case "bearer" -> AuthenticationType.BEARER;
                    case "apiKey" -> AuthenticationType.API_KEY;
                    case "oauth" -> AuthenticationType.OAUTH;
                    default -> throw new IllegalArgumentException("unsupported MCP auth type");
                };
        return new Authentication(
                type,
                textOrNull(value.get("credentialName")),
                textOrNull(value.get("headerName")),
                textOrNull(value.get("clientId")),
                strings(value.path("scopes"), "auth.scopes", 128));
    }

    private static Set<String> strings(JsonNode value, String name, int maximum) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return Set.of();
        }
        if (!value.isArray() || value.size() > maximum) {
            throw new IllegalArgumentException(name + " must be a bounded array");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        value.forEach(item -> {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new IllegalArgumentException(name + " contains an invalid value");
            }
            result.add(item.asText().strip());
        });
        return Set.copyOf(result);
    }

    private static void rejectUnknown(JsonNode object, Set<String> allowed, String path) {
        HashSet<String> unknown = new HashSet<>();
        object.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                unknown.add(name);
            }
        });
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(path + " contains unsupported fields: " + unknown);
        }
    }

    private static String requiredText(JsonNode value, String name) {
        if (!value.path(name).isTextual()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return required(value.path(name).asText(), name, 500);
    }

    private static String textOrNull(JsonNode value) {
        return value == null || value.isNull()
                ? null
                : value.isTextual() ? blankToNull(value.asText()) : throwInvalidText();
    }

    private static String throwInvalidText() {
        throw new IllegalArgumentException("MCP config text field has an invalid type");
    }

    private static String required(String value, String name, int maximum) {
        String result = Objects.requireNonNull(value, name).strip();
        if (result.isEmpty() || result.length() > maximum) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return result;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}
