package com.javaclaw.desktop.settings;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderReasoningSummary;

/**
 * Provider 表单的不可变草稿。
 *
 * @param id 稳定 Provider 标识
 * @param displayName 用户可见名称
 * @param adapter 适配器
 * @param baseUri 可空的自定义地址文本
 * @param authentication 鉴权方式
 * @param models 逐模型用途目录
 * @param credential 脱敏 CredentialRef
 * @param timeoutSeconds 请求超时秒数
 * @param maximumRetries 最大重试次数
 * @param organization OpenAI organization；不使用时为空文本
 * @param project OpenAI project；不使用时为空文本
 * @param apiVersion Google API version；使用默认值时为空文本
 * @param reasoningSummary OpenAI Responses reasoning summary 详细度
 * @param lifecycle 生命周期
 */
public record ProviderDraft(
        String id,
        String displayName,
        ProviderAdapter adapter,
        String baseUri,
        ProviderAuthentication authentication,
        List<ProviderModelSpec> models,
        Optional<CredentialRef> credential,
        int timeoutSeconds,
        int maximumRetries,
        String organization,
        String project,
        String apiVersion,
        ProviderReasoningSummary reasoningSummary,
        ProviderLifecycle lifecycle) {
    /** 规范化可空文本并保留尚未通过领域校验的用户输入。 */
    public ProviderDraft {
        id = Objects.requireNonNullElse(id, "");
        displayName = Objects.requireNonNullElse(displayName, "");
        Objects.requireNonNull(adapter, "adapter");
        baseUri = Objects.requireNonNullElse(baseUri, "");
        Objects.requireNonNull(authentication, "authentication");
        models = List.copyOf(Objects.requireNonNull(models, "models"));
        credential = Objects.requireNonNull(credential, "credential");
        organization = Objects.requireNonNullElse(organization, "");
        project = Objects.requireNonNullElse(project, "");
        apiVersion = Objects.requireNonNullElse(apiVersion, "");
        Objects.requireNonNull(reasoningSummary, "reasoningSummary");
        Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /** @return 新建 Provider 的可恢复禁用连接壳草稿 */
    public static ProviderDraft empty() {
        return new ProviderDraft(
                "",
                "",
                ProviderAdapter.OPENAI_COMPATIBLE,
                "",
                ProviderAuthentication.API_KEY,
                List.of(),
                Optional.empty(),
                60,
                0,
                "",
                "",
                "",
                ProviderReasoningSummary.AUTO,
                ProviderLifecycle.DISABLED);
    }

    /**
     * 创建已分配稳定标识的新 Provider 草稿。
     *
     * @param id Presenter 生成的稳定技术标识
     * @return 可继续填写的禁用连接壳草稿
     */
    public static ProviderDraft forNew(String id) {
        ProviderDraft empty = empty();
        return new ProviderDraft(
                Objects.requireNonNull(id, "id"),
                empty.displayName(),
                empty.adapter(),
                empty.baseUri(),
                empty.authentication(),
                empty.models(),
                empty.credential(),
                empty.timeoutSeconds(),
                empty.maximumRetries(),
                empty.organization(),
                empty.project(),
                empty.apiVersion(),
                empty.reasoningSummary(),
                empty.lifecycle());
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
        ProviderAdapterOptions options = spec.options();
        DraftAdapterOptions adapterOptions = draftOptions(options);
        return new ProviderDraft(
                checked.id(),
                spec.displayName(),
                spec.adapter(),
                spec.baseUri().map(URI::toString).orElse(""),
                spec.authentication(),
                spec.models(),
                spec.credential(),
                Math.toIntExact(spec.timeout().toSeconds()),
                spec.maximumRetries(),
                adapterOptions.organization(),
                adapterOptions.project(),
                adapterOptions.apiVersion(),
                adapterOptions.reasoningSummary(),
                checked.lifecycle());
    }

    /**
     * 将表单草稿转换为完整领域配置。
     *
     * @return 已通过 ProviderEndpointSpec 校验的配置
     */
    public ProviderEndpointSpec toSpec() {
        return new ProviderEndpointSpec(
                displayName,
                adapter,
                optionalUri(baseUri),
                authentication,
                models,
                credential,
                Duration.ofSeconds(timeoutSeconds),
                maximumRetries,
                adapterOptions());
    }

    /**
     * 替换逐模型目录。
     *
     * @param value 新目录
     * @return 新草稿
     */
    public ProviderDraft withModels(List<ProviderModelSpec> value) {
        return copy(List.copyOf(value), credential);
    }

    ProviderDraft withLifecycle(ProviderLifecycle value) {
        return new ProviderDraft(
                id,
                displayName,
                adapter,
                baseUri,
                authentication,
                models,
                credential,
                timeoutSeconds,
                maximumRetries,
                organization,
                project,
                apiVersion,
                reasoningSummary,
                Objects.requireNonNull(value, "value"));
    }

    /**
     * 仅替换 CredentialRef。
     *
     * @param reference 新引用
     * @return 新草稿
     */
    public ProviderDraft withCredential(Optional<CredentialRef> reference) {
        return copy(models, Objects.requireNonNull(reference, "reference"));
    }

    private ProviderDraft copy(List<ProviderModelSpec> modelCatalog, Optional<CredentialRef> reference) {
        return new ProviderDraft(
                id,
                displayName,
                adapter,
                baseUri,
                authentication,
                modelCatalog,
                reference,
                timeoutSeconds,
                maximumRetries,
                organization,
                project,
                apiVersion,
                reasoningSummary,
                lifecycle);
    }

    private static Optional<URI> optionalUri(String value) {
        String normalized = value.strip();
        return normalized.isEmpty() ? Optional.empty() : Optional.of(URI.create(normalized));
    }

    private static Optional<String> optionalText(String value) {
        String normalized = value.strip();
        return normalized.isEmpty() ? Optional.empty() : Optional.of(normalized);
    }

    private ProviderAdapterOptions adapterOptions() {
        return switch (adapter) {
            case OPENAI_COMPATIBLE ->
                new ProviderAdapterOptions.OpenAiCompatible(optionalText(organization), optionalText(project));
            case ANTHROPIC -> new ProviderAdapterOptions.Anthropic();
            case GOOGLE_GENAI -> new ProviderAdapterOptions.GoogleGenAi(optionalText(apiVersion));
            case OPENAI_RESPONSES ->
                new ProviderAdapterOptions.OpenAiResponses(
                        optionalText(organization), optionalText(project), reasoningSummary);
        };
    }

    private static DraftAdapterOptions draftOptions(ProviderAdapterOptions options) {
        return switch (options) {
            case ProviderAdapterOptions.OpenAiCompatible openAi ->
                new DraftAdapterOptions(
                        openAi.organization().orElse(""),
                        openAi.project().orElse(""),
                        "",
                        ProviderReasoningSummary.AUTO);
            case ProviderAdapterOptions.Anthropic ignored -> DraftAdapterOptions.empty();
            case ProviderAdapterOptions.GoogleGenAi google ->
                new DraftAdapterOptions("", "", google.apiVersion().orElse(""), ProviderReasoningSummary.AUTO);
            case ProviderAdapterOptions.OpenAiResponses responses ->
                new DraftAdapterOptions(
                        responses.organization().orElse(""),
                        responses.project().orElse(""),
                        "",
                        responses.reasoningSummary());
        };
    }

    private record DraftAdapterOptions(
            String organization, String project, String apiVersion, ProviderReasoningSummary reasoningSummary) {
        private static DraftAdapterOptions empty() {
            return new DraftAdapterOptions("", "", "", ProviderReasoningSummary.AUTO);
        }
    }
}
