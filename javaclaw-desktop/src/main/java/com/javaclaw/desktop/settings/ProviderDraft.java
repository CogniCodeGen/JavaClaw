package com.javaclaw.desktop.settings;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderRole;

/**
 * Provider 表单的不可变草稿。
 *
 * @param id 稳定 Provider 标识
 * @param displayName 用户可见名称
 * @param adapter 适配器
 * @param baseUri 可空的兼容端点文本
 * @param chat 是否承担 Chat 角色
 * @param embedding 是否承担 Embedding 角色
 * @param models 以逗号或换行分隔的模型目录
 * @param credential 脱敏 CredentialRef
 * @param timeoutSeconds 请求超时秒数
 * @param maximumRetries 最大重试次数
 * @param options 每行一个 key=value 的非敏感选项
 * @param lifecycle 生命周期
 */
public record ProviderDraft(
        String id,
        String displayName,
        ProviderAdapter adapter,
        String baseUri,
        boolean chat,
        boolean embedding,
        String models,
        Optional<CredentialRef> credential,
        int timeoutSeconds,
        int maximumRetries,
        String options,
        ProviderLifecycle lifecycle) {
    /** 规范化可空文本并保留尚未通过领域校验的用户输入。 */
    public ProviderDraft {
        id = Objects.requireNonNullElse(id, "");
        displayName = Objects.requireNonNullElse(displayName, "");
        adapter = Objects.requireNonNull(adapter, "adapter");
        baseUri = Objects.requireNonNullElse(baseUri, "");
        models = Objects.requireNonNullElse(models, "");
        credential = Objects.requireNonNull(credential, "credential");
        options = Objects.requireNonNullElse(options, "");
        lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /** @return 新建 Provider 的初始草稿 */
    public static ProviderDraft empty() {
        return new ProviderDraft(
                "",
                "",
                ProviderAdapter.OPENAI_COMPATIBLE,
                "",
                true,
                false,
                "",
                Optional.empty(),
                60,
                0,
                "",
                ProviderLifecycle.ACTIVE);
    }

    /**
     * 从权威 Provider 生成等价草稿。
     *
     * @param endpoint Provider 快照
     * @return 表单草稿
     */
    public static ProviderDraft from(ProviderEndpoint endpoint) {
        ProviderEndpoint checked = Objects.requireNonNull(endpoint, "endpoint");
        ProviderEndpointSpec spec = checked.spec();
        return new ProviderDraft(
                checked.id(),
                spec.displayName(),
                spec.adapter(),
                spec.baseUri().map(URI::toString).orElse(""),
                spec.roles().contains(ProviderRole.CHAT),
                spec.roles().contains(ProviderRole.EMBEDDING),
                String.join("\n", spec.models()),
                spec.credential(),
                Math.toIntExact(spec.timeout().toSeconds()),
                spec.maximumRetries(),
                formatOptions(spec.options()),
                checked.lifecycle());
    }

    /**
     * 将表单草稿转换为完整领域配置。
     *
     * @return 已通过 ProviderEndpointSpec 校验的配置
     */
    public ProviderEndpointSpec toSpec() {
        Set<ProviderRole> roles = new LinkedHashSet<>();
        if (chat) {
            roles.add(ProviderRole.CHAT);
        }
        if (embedding) {
            roles.add(ProviderRole.EMBEDDING);
        }
        return new ProviderEndpointSpec(
                displayName,
                adapter,
                optionalUri(baseUri),
                roles,
                values(models),
                credential,
                Duration.ofSeconds(timeoutSeconds),
                maximumRetries,
                parseOptions(options));
    }

    /**
     * 仅替换 CredentialRef。
     *
     * @param reference 新引用
     * @return 新草稿
     */
    public ProviderDraft withCredential(Optional<CredentialRef> reference) {
        return new ProviderDraft(
                id,
                displayName,
                adapter,
                baseUri,
                chat,
                embedding,
                models,
                reference,
                timeoutSeconds,
                maximumRetries,
                options,
                lifecycle);
    }

    private static Optional<URI> optionalUri(String value) {
        String normalized = value.strip();
        return normalized.isEmpty() ? Optional.empty() : Optional.of(URI.create(normalized));
    }

    private static List<String> values(String value) {
        return Arrays.stream(value.split("[,\\n]"))
                .map(String::strip)
                .filter(item -> !item.isEmpty())
                .distinct()
                .toList();
    }

    private static Map<String, String> parseOptions(String value) {
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String line : value.split("\\R")) {
            String normalized = line.strip();
            if (normalized.isEmpty()) {
                continue;
            }
            int separator = normalized.indexOf('=');
            if (separator < 1) {
                throw new IllegalArgumentException("Provider 选项必须使用 key=value 格式");
            }
            String key = normalized.substring(0, separator).strip();
            String optionValue = normalized.substring(separator + 1).strip();
            if (parsed.putIfAbsent(key, optionValue) != null) {
                throw new IllegalArgumentException("Provider 选项键重复: " + key);
            }
        }
        return Map.copyOf(parsed);
    }

    private static String formatOptions(Map<String, String> options) {
        return options.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}
