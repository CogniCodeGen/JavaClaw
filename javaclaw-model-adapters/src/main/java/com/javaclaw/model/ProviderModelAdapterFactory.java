package com.javaclaw.model;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.TurnId;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;

/** 从强类型 ProviderEndpoint 与 Vault 引用构造实际模型 Adapter。 */
public final class ProviderModelAdapterFactory {
    private final ProviderCredentialResolver credentials;

    /**
     * 创建工厂。
     *
     * @param credentials Secret Vault 读取边界
     */
    public ProviderModelAdapterFactory(ProviderCredentialResolver credentials) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    /**
     * 为一个精确 Provider/模型版本创建独立网关。
     *
     * @param endpoint Provider 版本
     * @param reference 精确模型引用
     * @return 模型网关；凭据不可用时返回 fail-closed 网关
     */
    public ModelGateway create(ProviderEndpoint endpoint, ProviderRef reference) {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(reference, "reference");
        if (!endpoint.id().equals(reference.endpointId()) || endpoint.revision() != reference.endpointRevision()) {
            throw new IllegalArgumentException("ProviderRef does not match endpoint version");
        }
        ProviderModelSpec model = endpoint.spec().models().stream()
                .filter(candidate -> candidate.modelId().equals(reference.model()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("model is not declared by Provider endpoint"));
        if (!model.supports(ProviderModelPurpose.CHAT)) {
            throw new IllegalArgumentException("Provider model does not declare the CHAT purpose");
        }
        validateConfiguration(endpoint, reference);
        if (endpoint.spec().authentication() == ProviderAuthentication.NONE) {
            return create(endpoint, reference, new char[0]);
        }
        return endpoint.spec()
                .credential()
                .flatMap(credentials::resolve)
                .<ModelGateway>map(material -> create(endpoint, reference, material))
                .orElseGet(() -> new UnavailableCredentialGateway(reference));
    }

    private ModelGateway create(ProviderEndpoint endpoint, ProviderRef reference, CredentialMaterial material) {
        try (material) {
            char[] secret = material.copy();
            try {
                return create(endpoint, reference, secret);
            } finally {
                Arrays.fill(secret, '\0');
            }
        }
    }

    private ModelGateway create(ProviderEndpoint endpoint, ProviderRef reference, char[] secret) {
        return switch (endpoint.spec().adapter()) {
            case OPENAI_COMPATIBLE, ANTHROPIC, GOOGLE_GENAI -> spring(endpoint, reference, secret);
            case OPENAI_RESPONSES -> responses(endpoint, reference, secret);
        };
    }

    private static ModelGateway spring(ProviderEndpoint endpoint, ProviderRef reference, char[] secret) {
        SpringAdapterOptions options = springOptions(endpoint.spec().options());
        SpringAiEndpointConfig config = new SpringAiEndpointConfig(
                reference.routeKey(),
                springProvider(endpoint.spec().adapter()),
                reference.model(),
                endpoint.spec().baseUri(),
                endpoint.spec().authentication(),
                options.organization(),
                options.project(),
                options.apiVersion(),
                endpoint.spec().timeout(),
                endpoint.spec().maximumRetries());
        return SpringAiModelAdapter.builder().register(config, secret).build();
    }

    private static ModelGateway responses(ProviderEndpoint endpoint, ProviderRef reference, char[] secret) {
        ProviderAdapterOptions.OpenAiResponses options =
                responsesOptions(endpoint.spec().options());
        ReasoningSummaryStyle summary =
                ReasoningSummaryStyle.valueOf(options.reasoningSummary().name());
        OpenAiResponsesEndpointConfig config = new OpenAiResponsesEndpointConfig(
                reference.routeKey(),
                reference.model(),
                endpoint.spec().baseUri(),
                endpoint.spec().timeout(),
                endpoint.spec().maximumRetries(),
                summary);
        var client = OpenAiSdkClientFactory.create(
                endpoint.spec().baseUri(),
                endpoint.spec().authentication(),
                options.organization(),
                options.project(),
                endpoint.spec().timeout(),
                endpoint.spec().maximumRetries(),
                secret);
        return OpenAiResponsesModelAdapter.create(config, client);
    }

    private static void validateConfiguration(ProviderEndpoint endpoint, ProviderRef reference) {
        switch (endpoint.spec().adapter()) {
            case OPENAI_COMPATIBLE, ANTHROPIC, GOOGLE_GENAI -> {
                SpringAdapterOptions options = springOptions(endpoint.spec().options());
                new SpringAiEndpointConfig(
                        reference.routeKey(),
                        springProvider(endpoint.spec().adapter()),
                        reference.model(),
                        endpoint.spec().baseUri(),
                        endpoint.spec().authentication(),
                        options.organization(),
                        options.project(),
                        options.apiVersion(),
                        endpoint.spec().timeout(),
                        endpoint.spec().maximumRetries());
            }
            case OPENAI_RESPONSES -> responsesOptions(endpoint.spec().options());
        }
    }

    private static SpringAdapterOptions springOptions(ProviderAdapterOptions options) {
        return switch (options) {
            case ProviderAdapterOptions.OpenAiCompatible openAi ->
                new SpringAdapterOptions(openAi.organization(), openAi.project(), Optional.empty());
            case ProviderAdapterOptions.Anthropic ignored -> SpringAdapterOptions.empty();
            case ProviderAdapterOptions.GoogleGenAi google ->
                new SpringAdapterOptions(Optional.empty(), Optional.empty(), google.apiVersion());
            case ProviderAdapterOptions.OpenAiResponses ignored ->
                throw new IllegalArgumentException("OpenAI Responses does not use Spring AI options");
        };
    }

    private static ProviderAdapterOptions.OpenAiResponses responsesOptions(ProviderAdapterOptions options) {
        if (options instanceof ProviderAdapterOptions.OpenAiResponses responses) {
            return responses;
        }
        throw new IllegalArgumentException("OpenAI Responses requires its matching options");
    }

    private static SpringAiProvider springProvider(ProviderAdapter adapter) {
        return switch (adapter) {
            case OPENAI_COMPATIBLE -> SpringAiProvider.OPENAI_COMPATIBLE;
            case ANTHROPIC -> SpringAiProvider.ANTHROPIC;
            case GOOGLE_GENAI -> SpringAiProvider.GOOGLE_GENAI;
            case OPENAI_RESPONSES -> throw new IllegalArgumentException("Responses does not use Spring AI");
        };
    }

    private record SpringAdapterOptions(
            Optional<String> organization, Optional<String> project, Optional<String> apiVersion) {
        private static SpringAdapterOptions empty() {
            return new SpringAdapterOptions(Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    /** 凭据不可用时连 capabilities 查询都拒绝，避免接受永远不能执行的 Turn。 */
    private static final class UnavailableCredentialGateway implements ModelGateway {
        private final ProviderRef reference;

        private UnavailableCredentialGateway(ProviderRef reference) {
            this.reference = reference;
        }

        @Override
        public ModelCapabilities capabilities(String modelId) {
            throw unavailable();
        }

        @Override
        public ModelInvocationResult invoke(
                TurnId turnId, ModelInvocation invocation, ModelEventSink events, CancellationToken cancellation) {
            throw unavailable();
        }

        private IllegalStateException unavailable() {
            return new IllegalStateException("Provider credential is unavailable: " + reference.endpointId());
        }
    }
}
