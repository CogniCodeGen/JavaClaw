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
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderRole;
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
     * @param model Provider 原生模型
     * @return 可关闭端口；凭据或 Provider 不支持时安全拒绝
     */
    public EmbeddingPort create(ProviderEndpoint endpoint, String model) {
        ProviderEndpoint checked = Objects.requireNonNull(endpoint, "endpoint");
        String checkedModel = Objects.requireNonNull(model, "model").strip();
        if (!checked.spec().roles().contains(ProviderRole.EMBEDDING)
                || !checked.spec().models().contains(checkedModel)) {
            throw new IllegalArgumentException("Provider endpoint does not declare this Embedding model");
        }
        dimensions(checked);
        validateDefaultSelection(checked);
        if (checked.spec().adapter() == ProviderAdapter.ANTHROPIC) {
            return EmbeddingPort.unavailable();
        }
        return checked.spec()
                .credential()
                .flatMap(credentials::resolve)
                .<EmbeddingPort>map(material -> create(checked, checkedModel, material))
                .orElseGet(EmbeddingPort::unavailable);
    }

    private EmbeddingPort create(ProviderEndpoint endpoint, String model, CredentialMaterial material) {
        try (material) {
            char[] secret = material.copy();
            try {
                return switch (endpoint.spec().adapter()) {
                    case OPENAI_COMPATIBLE, OPENAI_RESPONSES -> openAi(endpoint, model, secret);
                    case GOOGLE_GENAI -> google(endpoint, model, secret);
                    case ANTHROPIC -> EmbeddingPort.unavailable();
                };
            } finally {
                Arrays.fill(secret, '\0');
            }
        }
    }

    private EmbeddingPort openAi(ProviderEndpoint endpoint, String model, char[] secret) {
        String apiKey = requiredSecret(secret);
        OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(endpoint.spec().timeout())
                .maxRetries(endpoint.spec().maximumRetries());
        endpoint.spec().baseUri().ifPresent(uri -> clientBuilder.baseUrl(uri.toString()));
        OpenAIClient client = clientBuilder.build();
        OptionalInt dimensions = dimensions(endpoint);
        OpenAiEmbeddingOptions.Builder options =
                OpenAiEmbeddingOptions.builder().model(model);
        dimensions.ifPresent(options::dimensions);
        OpenAiEmbeddingModel embedding = OpenAiEmbeddingModel.builder()
                .openAiClient(client)
                .options(options.build())
                .build();
        return new SpringAiEmbeddingPort(
                embedding, fingerprint(endpoint, model, dimensions), dimensions, client::close);
    }

    private EmbeddingPort google(ProviderEndpoint endpoint, String model, char[] secret) {
        HttpOptions.Builder http = HttpOptions.builder()
                .timeout(Math.toIntExact(endpoint.spec().timeout().toMillis()));
        endpoint.spec().baseUri().ifPresent(uri -> http.baseUrl(uri.toString()));
        Client client = Client.builder()
                .apiKey(requiredSecret(secret))
                .httpOptions(http.build())
                .build();
        OptionalInt dimensions = dimensions(endpoint);
        return new GoogleGenAiEmbeddingPort(client, model, fingerprint(endpoint, model, dimensions), dimensions);
    }

    private static OptionalInt dimensions(ProviderEndpoint endpoint) {
        String configured = endpoint.spec().options().get("embeddingDimensions");
        if (configured == null) {
            return OptionalInt.empty();
        }
        int parsed;
        try {
            parsed = Integer.parseInt(configured);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("embeddingDimensions must be an integer", failure);
        }
        if (parsed < 1 || parsed > 65_536) {
            throw new IllegalArgumentException("embeddingDimensions must be between 1 and 65536");
        }
        return OptionalInt.of(parsed);
    }

    private static void validateDefaultSelection(ProviderEndpoint endpoint) {
        String configured = endpoint.spec().options().get("defaultEmbedding");
        if (configured != null && !configured.equalsIgnoreCase("true") && !configured.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException("defaultEmbedding must be true or false");
        }
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
}
