package com.javaclaw.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.OptionalInt;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.openai.client.OpenAIClient;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.extension.spi.EmbeddingPort;

/** 从强类型 ProviderEndpoint 构造 EmbeddingPort；Secret 只在 Adapter 构造期短暂存在。 */
public final class ProviderEmbeddingAdapterFactory {
    private final ProviderCredentialResolver credentials;

    /**
     * 创建工厂。
     *
     * @param credentials Secret Vault 读取边界
     */
    public ProviderEmbeddingAdapterFactory(ProviderCredentialResolver credentials) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    /**
     * 为精确 Provider 版本和模型创建 Embedding 端口。
     *
     * @param endpoint Provider 版本
     * @param reference 精确 Provider 与模型引用
     * @return 可关闭端口；凭据或 Provider 不支持时安全拒绝
     */
    public EmbeddingPort create(ProviderEndpoint endpoint, ProviderRef reference) {
        ProviderEndpoint checked = Objects.requireNonNull(endpoint, "endpoint");
        ProviderRef checkedReference = Objects.requireNonNull(reference, "reference");
        if (!checked.id().equals(checkedReference.endpointId())
                || checked.revision() != checkedReference.endpointRevision()) {
            throw new IllegalArgumentException("ProviderRef does not match endpoint version");
        }
        ProviderModelSpec model = checked.spec().models().stream()
                .filter(candidate -> candidate.modelId().equals(checkedReference.model()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Embedding model is not declared by Provider"));
        if (!model.supports(ProviderModelPurpose.EMBEDDING)) {
            throw new IllegalArgumentException("Provider endpoint does not declare this Embedding model");
        }
        if (checked.spec().adapter() == ProviderAdapter.ANTHROPIC
                || checked.spec().adapter() == ProviderAdapter.OPENAI_RESPONSES) {
            throw new IllegalArgumentException("Provider adapter does not support Embedding");
        }
        if (checked.spec().authentication() == ProviderAuthentication.NONE) {
            return create(checked, model, new char[0]);
        }
        return checked.spec()
                .credential()
                .flatMap(credentials::resolve)
                .<EmbeddingPort>map(material -> create(checked, model, material))
                .orElseGet(EmbeddingPort::unavailable);
    }

    private EmbeddingPort create(ProviderEndpoint endpoint, ProviderModelSpec model, CredentialMaterial material) {
        try (material) {
            char[] secret = material.copy();
            try {
                return create(endpoint, model, secret);
            } finally {
                Arrays.fill(secret, '\0');
            }
        }
    }

    private EmbeddingPort create(ProviderEndpoint endpoint, ProviderModelSpec model, char[] secret) {
        return switch (endpoint.spec().adapter()) {
            case OPENAI_COMPATIBLE -> openAi(endpoint, model, secret);
            case GOOGLE_GENAI -> google(endpoint, model, secret);
            case ANTHROPIC, OPENAI_RESPONSES ->
                throw new IllegalArgumentException("Provider adapter does not support Embedding");
        };
    }

    private EmbeddingPort openAi(ProviderEndpoint endpoint, ProviderModelSpec model, char[] secret) {
        ProviderAdapterOptions.OpenAiCompatible providerOptions =
                openAiOptions(endpoint.spec().options());
        OpenAIClient client = OpenAiSdkClientFactory.create(
                endpoint.spec().baseUri(),
                endpoint.spec().authentication(),
                providerOptions.organization(),
                providerOptions.project(),
                endpoint.spec().timeout(),
                endpoint.spec().maximumRetries(),
                secret);
        OptionalInt dimensions = model.embeddingDimensions();
        OpenAiEmbeddingOptions.Builder options =
                OpenAiEmbeddingOptions.builder().model(model.modelId());
        dimensions.ifPresent(options::dimensions);
        OpenAiEmbeddingModel embedding = OpenAiEmbeddingModel.builder()
                .openAiClient(client)
                .options(options.build())
                .build();
        return new SpringAiEmbeddingPort(
                embedding, fingerprint(endpoint, model.modelId(), dimensions), dimensions, client::close);
    }

    private EmbeddingPort google(ProviderEndpoint endpoint, ProviderModelSpec model, char[] secret) {
        ProviderAdapterOptions.GoogleGenAi options =
                googleOptions(endpoint.spec().options());
        HttpOptions.Builder http = HttpOptions.builder()
                .timeout(Math.toIntExact(endpoint.spec().timeout().toMillis()));
        endpoint.spec().baseUri().ifPresent(uri -> http.baseUrl(uri.toString()));
        options.apiVersion().ifPresent(http::apiVersion);
        Client client = Client.builder()
                .apiKey(requiredSecret(secret))
                .httpOptions(http.build())
                .build();
        OptionalInt dimensions = model.embeddingDimensions();
        return new GoogleGenAiEmbeddingPort(
                client, model.modelId(), fingerprint(endpoint, model.modelId(), dimensions), dimensions);
    }

    private static String fingerprint(ProviderEndpoint endpoint, String model, OptionalInt dimensions) {
        String identity = endpoint.id() + "\n" + endpoint.revision() + "\n"
                + endpoint.spec().adapter() + "\n" + model
                + "\n" + endpoint.spec().baseUri().map(Object::toString).orElse("") + "\n"
                + (dimensions.isPresent() ? dimensions.getAsInt() : "provider-default");
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String requiredSecret(char[] value) {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException("credential must not be empty");
        }
        return new String(value);
    }

    private static ProviderAdapterOptions.OpenAiCompatible openAiOptions(ProviderAdapterOptions options) {
        if (options instanceof ProviderAdapterOptions.OpenAiCompatible openAi) {
            return openAi;
        }
        throw new IllegalArgumentException("OpenAI-compatible Embedding requires its matching options");
    }

    private static ProviderAdapterOptions.GoogleGenAi googleOptions(ProviderAdapterOptions options) {
        if (options instanceof ProviderAdapterOptions.GoogleGenAi google) {
            return google;
        }
        throw new IllegalArgumentException("Google Embedding requires its matching options");
    }
}
