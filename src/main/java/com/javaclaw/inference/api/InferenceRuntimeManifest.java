package com.javaclaw.inference.api;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 已安装推理运行时的签名清单。 */
public record InferenceRuntimeManifest(
        String runtimeId,
        String engine,
        String engineVersion,
        String adapterVersion,
        ProtocolVersion protocol,
        String platform,
        String architecture,
        int minimumJavaVersion,
        Set<String> capabilities,
        Map<String, Object> parameterSchema,
        List<RuntimeFile> files,
        boolean pureJavaFallback) {

    public InferenceRuntimeManifest {
        runtimeId = requireText(runtimeId, "运行时 ID");
        engine = requireText(engine, "引擎");
        engineVersion = requireText(engineVersion, "引擎版本");
        adapterVersion = requireText(adapterVersion, "适配器版本");
        if (protocol == null) throw new IllegalArgumentException("协议版本不能为空");
        platform = requireText(platform, "平台");
        architecture = requireText(architecture, "架构");
        if (minimumJavaVersion < 21) throw new IllegalArgumentException("运行时最低 Java 版本无效");
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        parameterSchema = parameterSchema == null ? Map.of() : Map.copyOf(parameterSchema);
        files = files == null ? List.of() : List.copyOf(files);
    }

    /** 运行时通过签名 capability 声明可接受的 Hugging Face model_type。 */
    public Set<String> supportedModelTypes(InferenceModelProfile.Kind kind) {
        if (kind == null) return Set.of();
        String prefix = "model-type:" + kind.name().toLowerCase(Locale.ROOT) + ":";
        return capabilities.stream().filter(value -> value.startsWith(prefix))
                .map(value -> value.substring(prefix.length()).toLowerCase(Locale.ROOT))
                .filter(value -> value.matches("[a-z0-9][a-z0-9_-]{0,127}"))
                .collect(Collectors.toUnmodifiableSet());
    }

    public static String modelTypeCapability(InferenceModelProfile.Kind kind, String modelType) {
        if (kind == null) throw new IllegalArgumentException("模型用途不能为空");
        String normalized = requireText(modelType, "模型类型").toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_-]{0,127}")) {
            throw new IllegalArgumentException("模型类型格式无效");
        }
        return "model-type:" + kind.name().toLowerCase(Locale.ROOT) + ":" + normalized;
    }

    public record ProtocolVersion(int major, int minor) {
        public ProtocolVersion {
            if (major <= 0 || minor < 0) throw new IllegalArgumentException("协议版本无效");
        }

        public boolean compatibleWith(ProtocolVersion host) {
            return host != null && major == host.major && minor <= host.minor;
        }
    }

    public record RuntimeFile(String path, String sha256, long size) {
        public RuntimeFile {
            path = requireText(path, "运行时文件路径");
            sha256 = requireSha256(sha256);
            if (size < 0) throw new IllegalArgumentException("运行时文件长度不能为负数");
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.strip();
    }

    private static String requireSha256(String value) {
        String normalized = requireText(value, "SHA-256").toLowerCase();
        if (!normalized.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("SHA-256 格式无效");
        return normalized;
    }
}
