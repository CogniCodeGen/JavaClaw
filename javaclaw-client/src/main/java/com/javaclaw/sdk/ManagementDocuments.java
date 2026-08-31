package com.javaclaw.sdk;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;

import com.javaclaw.sdk.model.AutomationInfo;
import com.javaclaw.sdk.model.BrowserSiteInfo;
import com.javaclaw.sdk.model.JsonDocument;
import com.javaclaw.sdk.model.MemoryDetailInfo;
import com.javaclaw.sdk.model.NetworkGrantInfo;
import com.javaclaw.sdk.model.ProfileInfo;
import com.javaclaw.sdk.model.ScheduleInfo;
import com.javaclaw.sdk.model.SkillLearningDraft;
import com.javaclaw.sdk.model.ToolAuthorizationInfo;

/** 本机管理表单/文件的类型化解析器；只产生 SDK 草稿，不保存、授权或调用任意 RPC。 */
public final class ManagementDocuments {
    private static final ObjectMapper JSON = mapper();

    private ManagementDocuments() {}

    /** 解析 Profile 草稿；systemPrompt 仅是可编辑层，服务端继续验证权限与版本。 */
    public static ProfileInfo profile(JsonDocument document) {
        return read(document, ProfileInfo.class);
    }

    /** 解析自动化元数据及内嵌定义；定义使用普通 JSON 对象而非字符串转义。 */
    public static AutomationInfo automation(JsonDocument document) {
        return read(document, AutomationInfo.class);
    }

    /** 解析有限 Schedule 定义；时区、cron 和安全策略仍由服务端验证。 */
    public static ScheduleInfo schedule(JsonDocument document) {
        return read(document, ScheduleInfo.class);
    }

    /** 解析带固定状态、主体与来源的 Memory 草稿，不自动确认冲突。 */
    public static MemoryDetailInfo memory(JsonDocument document) {
        return read(document, MemoryDetailInfo.class);
    }

    /** 解析带执行证据的 Skill 提案，不自动安装或启用脚本。 */
    public static SkillLearningDraft skillProposal(JsonDocument document) {
        return read(document, SkillLearningDraft.class);
    }

    /** 解析准确来源的站点配置，不接受原始 Cookie 或凭据字段。 */
    public static BrowserSiteInfo site(JsonDocument document) {
        return read(document, BrowserSiteInfo.class);
    }

    /** 解析准确 IP、用途、工作区与有限期限的网络授权，保存仍需显式确认。 */
    public static NetworkGrantInfo networkGrant(JsonDocument document) {
        return read(document, NetworkGrantInfo.class);
    }

    /** 解析工具 Schema/revision 绑定的有限预授权；参数模板作为普通 JSON 对象输入。 */
    public static ToolAuthorizationInfo toolAuthorization(JsonDocument document) {
        return read(document, ToolAuthorizationInfo.class);
    }

    /** 解析最多 64 个非空名称/字符串值的配置项，不允许嵌套对象或隐式数值转换。 */
    public static Map<String, String> stringMap(JsonDocument document) {
        try {
            var node = JSON.readTree(bounded(document));
            if (!node.isObject() || node.size() > 64) {
                throw new IllegalArgumentException("configuration must be a bounded string map");
            }
            var result = new LinkedHashMap<String, String>();
            for (var field : node.properties()) {
                if (field.getKey().isBlank()
                        || field.getKey().length() > 256
                        || !field.getValue().isTextual()) {
                    throw new IllegalArgumentException("configuration values must be strings");
                }
                result.put(field.getKey(), field.getValue().asText());
            }
            return Map.copyOf(result);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("invalid configuration document");
        }
    }

    private static <T> T read(JsonDocument source, Class<T> type) {
        try {
            T value = JSON.readValue(bounded(source), type);
            if (value == null) {
                throw new IllegalArgumentException("document must contain an object");
            }
            return value;
        } catch (IOException invalid) {
            // Jackson 异常可能包含用户文件或敏感输入片段；只报告契约类别。
            throw new IllegalArgumentException("invalid " + type.getSimpleName() + " document");
        }
    }

    private static String bounded(JsonDocument document) {
        String value = java.util.Objects.requireNonNull(document).canonicalJson();
        if (value.length() > 1024 * 1024) {
            throw new IllegalArgumentException("management document exceeds 1 MiB");
        }
        return value;
    }

    private static ObjectMapper mapper() {
        var mapper = new com.javaclaw.protocol.JsonRpcCodec().mapper().copy();
        mapper.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);
        var module = new SimpleModule();
        module.addDeserializer(JsonDocument.class, new JsonDeserializer<>() {
            @Override
            public JsonDocument deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                return new JsonDocument(context.readTree(parser).toString());
            }
        });
        mapper.registerModule(module);
        return mapper;
    }
}
