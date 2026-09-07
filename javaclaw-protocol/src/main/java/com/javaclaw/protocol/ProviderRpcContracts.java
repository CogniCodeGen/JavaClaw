package com.javaclaw.protocol;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.EmbeddingBinding;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderRef;

/** Provider 与 Embedding 绑定的 Protocol v3 payload。 */
public final class ProviderRpcContracts {
    private ProviderRpcContracts() {}

    /**
     * Provider 精确版本查询。
     *
     * @param id Provider 标识
     * @param revision 精确版本号
     */
    public record ProviderReadPayload(String id, long revision) {
        /** 校验查询。 */
        public ProviderReadPayload {
            id = identifier(id, "id");
            revision = positive(revision, "revision");
        }
    }

    /**
     * Provider 创建参数。
     *
     * @param id Provider 标识
     * @param spec Provider 配置
     * @param lifecycle 初始生命周期；允许创建禁用的空模型连接壳
     */
    public record ProviderCreatePayload(String id, ProviderEndpointSpec spec, ProviderLifecycle lifecycle) {
        /** 校验创建参数。 */
        public ProviderCreatePayload {
            id = identifier(id, "id");
            Objects.requireNonNull(spec, "spec");
            requireMutableLifecycle(lifecycle);
            requireModelsWhenActive(spec, lifecycle);
            if (spec.authentication() == com.javaclaw.api.ProviderAuthentication.API_KEY) {
                if (lifecycle != ProviderLifecycle.DISABLED || spec.credential().isPresent()) {
                    throw new IllegalArgumentException(
                            "API_KEY Provider must be created as a credential-free disabled shell");
                }
            }
        }
    }

    /**
     * Provider 完整更新参数。
     *
     * @param id Provider 标识
     * @param spec Provider 配置
     * @param lifecycle Provider 生命周期状态
     */
    public record ProviderUpdatePayload(String id, ProviderEndpointSpec spec, ProviderLifecycle lifecycle) {
        /** 校验更新参数。 */
        public ProviderUpdatePayload {
            id = identifier(id, "id");
            Objects.requireNonNull(spec, "spec");
            requireMutableLifecycle(lifecycle);
            requireModelsWhenActive(spec, lifecycle);
        }
    }

    /**
     * Provider 归档参数。
     *
     * @param id Provider 标识
     */
    public record ProviderArchivePayload(String id) {
        /** 校验标识。 */
        public ProviderArchivePayload {
            id = identifier(id, "id");
        }
    }

    /**
     * Provider 本地探测参数。
     *
     * @param provider Provider 精确版本引用
     */
    public record ProviderProbePayload(ProviderRef provider) {
        /** 校验引用。 */
        public ProviderProbePayload {
            Objects.requireNonNull(provider, "provider");
        }
    }

    /** 本地安装默认 Embedding 绑定查询参数。 */
    public record EmbeddingBindingReadPayload() {
        /** 创建无字段查询参数。 */
        public EmbeddingBindingReadPayload {}
    }

    /**
     * 本地安装默认 Embedding 绑定更新。
     *
     * @param provider 精确 Provider 与 Embedding 模型引用
     */
    public record EmbeddingBindingUpdatePayload(ProviderRef provider) {
        /** 校验模型引用。 */
        public EmbeddingBindingUpdatePayload {
            Objects.requireNonNull(provider, "provider");
        }
    }

    /**
     * Provider 最新版本列表。
     *
     * @param providers Provider 最新版本快照
     */
    public record ProviderListResult(List<ProviderEndpoint> providers) {
        /** 复制结果。 */
        public ProviderListResult {
            providers = List.copyOf(providers);
        }
    }

    /**
     * 可空的本地安装默认 Embedding 绑定查询结果。
     *
     * @param binding 未配置时为空
     */
    public record EmbeddingBindingReadResult(Optional<EmbeddingBinding> binding) {
        /** 校验可选结果。 */
        public EmbeddingBindingReadResult {
            binding = Objects.requireNonNull(binding, "binding");
        }
    }

    private static String identifier(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return normalized;
    }

    private static long positive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static void requireMutableLifecycle(ProviderLifecycle lifecycle) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        if (lifecycle == ProviderLifecycle.ARCHIVED) {
            throw new IllegalArgumentException("Provider create/update lifecycle must not be ARCHIVED");
        }
    }

    private static void requireModelsWhenActive(ProviderEndpointSpec spec, ProviderLifecycle lifecycle) {
        if (lifecycle == ProviderLifecycle.ACTIVE && spec.models().isEmpty()) {
            throw new IllegalArgumentException("active Provider must declare at least one model");
        }
    }
}
