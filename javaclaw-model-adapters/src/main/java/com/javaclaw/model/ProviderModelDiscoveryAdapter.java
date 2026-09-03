package com.javaclaw.model;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.models.ModelListParams;
import com.google.genai.Client;
import com.google.genai.Pager;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.ListModelsConfig;
import com.google.genai.types.Model;
import com.openai.client.OpenAIClient;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderModelDiscoveryCandidate;
import com.javaclaw.api.ProviderModelDiscoveryResult;
import com.javaclaw.api.ProviderModelPurpose;

/** 使用厂商官方 SDK 读取模型元数据，不执行推理。 */
public final class ProviderModelDiscoveryAdapter {
    private static final int MAXIMUM_MODELS = 1_000;
    private static final Duration MAXIMUM_TIMEOUT = Duration.ofSeconds(30);

    private final ProviderCredentialResolver credentials;
    private final Clock clock;

    /**
     * 创建模型目录发现适配器。
     *
     * @param credentials Secret Vault 读取边界
     * @param clock 服务端时钟
     */
    public ProviderModelDiscoveryAdapter(ProviderCredentialResolver credentials, Clock clock) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 读取一个已保存 Provider 精确版本的模型目录。
     *
     * @param endpoint 已由 App Server 校验的精确 Provider 版本
     * @param cancellation 协作式取消信号
     * @return 最多 1000 条、不持久化的候选
     */
    public ProviderModelDiscoveryResult discover(ProviderEndpoint endpoint, CancellationToken cancellation) {
        ProviderEndpoint checked = Objects.requireNonNull(endpoint, "endpoint");
        CancellationToken checkedCancellation = Objects.requireNonNull(cancellation, "cancellation");
        checkedCancellation.throwIfCancelled();
        DiscoveryPage page;
        if (checked.spec().authentication() == ProviderAuthentication.NONE) {
            page = discover(checked, new char[0], checkedCancellation);
        } else {
            CredentialMaterial material = checked.spec()
                    .credential()
                    .flatMap(credentials::resolve)
                    .orElseThrow(() -> new IllegalStateException("Provider credential is unavailable"));
            try (material) {
                char[] secret = material.copy();
                try {
                    page = discover(checked, secret, checkedCancellation);
                } finally {
                    Arrays.fill(secret, '\0');
                }
            }
        }
        return new ProviderModelDiscoveryResult(
                checked.id(), checked.revision(), page.candidates(), page.truncated(), clock.instant());
    }

    private DiscoveryPage discover(ProviderEndpoint endpoint, char[] secret, CancellationToken cancellation) {
        return switch (endpoint.spec().adapter()) {
            case OPENAI_COMPATIBLE, OPENAI_RESPONSES -> openAi(endpoint, secret, cancellation);
            case ANTHROPIC -> anthropic(endpoint, secret, cancellation);
            case GOOGLE_GENAI -> google(endpoint, secret, cancellation);
        };
    }

    private DiscoveryPage openAi(ProviderEndpoint endpoint, char[] secret, CancellationToken cancellation) {
        Duration timeout = discoveryTimeout(endpoint);
        OpenAiDiscoveryOptions options = openAiOptions(endpoint.spec().options());
        OpenAIClient client = OpenAiSdkClientFactory.discovery(
                endpoint.spec().baseUri(),
                endpoint.spec().authentication(),
                options.organization(),
                options.project(),
                timeout,
                secret,
                cancellation);
        try {
            List<com.openai.models.models.Model> models = client.models().list().data();
            Set<ProviderModelPurpose> purposes = endpoint.spec().adapter() == ProviderAdapter.OPENAI_RESPONSES
                    ? Set.of(ProviderModelPurpose.CHAT)
                    : Set.of();
            LinkedHashMap<String, ProviderModelDiscoveryCandidate> candidates = new LinkedHashMap<>();
            for (com.openai.models.models.Model model : models) {
                cancellation.throwIfCancelled();
                candidates.putIfAbsent(model.id(), candidate(model.id(), model.id(), purposes));
                if (candidates.size() > MAXIMUM_MODELS) {
                    break;
                }
            }
            return bounded(candidates.values());
        } finally {
            client.close();
        }
    }

    private DiscoveryPage anthropic(ProviderEndpoint endpoint, char[] secret, CancellationToken cancellation) {
        String baseUrl = endpoint.spec().baseUri().map(Object::toString).orElse("https://api.anthropic.com");
        com.anthropic.core.http.HttpClient transport =
                DiscoveryHttpClients.anthropic(discoveryTimeout(endpoint), baseUrl, cancellation);
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(requiredSecret(secret))
                .addInterceptor(ignored -> transport)
                .timeout(discoveryTimeout(endpoint))
                .maxRetries(0);
        endpoint.spec().baseUri().ifPresent(uri -> builder.baseUrl(uri.toString()));
        AnthropicClient client = builder.build();
        try {
            var page = client.models()
                    .list(ModelListParams.builder().limit(MAXIMUM_MODELS).build());
            LinkedHashMap<String, ProviderModelDiscoveryCandidate> candidates = new LinkedHashMap<>();
            int inspected = 0;
            int pages = 0;
            while (true) {
                pages++;
                for (var model : page.data()) {
                    cancellation.throwIfCancelled();
                    if (inspected >= MAXIMUM_MODELS) {
                        return new DiscoveryPage(List.copyOf(candidates.values()), true);
                    }
                    inspected++;
                    candidates.putIfAbsent(
                            model.id(), candidate(model.id(), model.displayName(), Set.of(ProviderModelPurpose.CHAT)));
                }
                if (!page.hasMore().orElse(false)) {
                    return new DiscoveryPage(List.copyOf(candidates.values()), false);
                }
                if (inspected >= MAXIMUM_MODELS || pages >= DiscoveryHttpClients.MAXIMUM_REQUESTS) {
                    return new DiscoveryPage(List.copyOf(candidates.values()), true);
                }
                page = page.nextPage();
            }
        } finally {
            try {
                client.close();
            } finally {
                transport.close();
            }
        }
    }

    private DiscoveryPage google(ProviderEndpoint endpoint, char[] secret, CancellationToken cancellation) {
        ProviderAdapterOptions.GoogleGenAi options =
                googleOptions(endpoint.spec().options());
        HttpOptions.Builder http = HttpOptions.builder()
                .timeout(Math.toIntExact(discoveryTimeout(endpoint).toMillis()));
        endpoint.spec().baseUri().ifPresent(uri -> http.baseUrl(uri.toString()));
        options.apiVersion().ifPresent(http::apiVersion);
        okhttp3.OkHttpClient httpClient = DiscoveryHttpClients.google(discoveryTimeout(endpoint), cancellation);
        com.google.genai.types.ClientOptions clientOptions = com.google.genai.types.ClientOptions.builder()
                .customHttpClient(httpClient)
                .build();
        try (Client client = Client.builder()
                .apiKey(requiredSecret(secret))
                .clientOptions(clientOptions)
                .httpOptions(http.build())
                .build()) {
            Pager<Model> pager = client.models.list(ListModelsConfig.builder()
                    .pageSize(MAXIMUM_MODELS)
                    .queryBase(true)
                    .build());
            LinkedHashMap<String, ProviderModelDiscoveryCandidate> models = new LinkedHashMap<>();
            Iterator<Model> iterator = pager.iterator();
            int inspected = 0;
            while (inspected < MAXIMUM_MODELS && iterator.hasNext()) {
                cancellation.throwIfCancelled();
                Model model = iterator.next();
                inspected++;
                String id = model.name().orElseThrow(() -> new IllegalStateException("Google model has no name"));
                String displayName = model.displayName().orElse(id);
                models.putIfAbsent(id, candidate(id, displayName, googlePurposes(model)));
            }
            return new DiscoveryPage(List.copyOf(models.values()), iterator.hasNext());
        } finally {
            DiscoveryHttpClients.close(httpClient);
        }
    }

    private static Set<ProviderModelPurpose> googlePurposes(Model model) {
        java.util.EnumSet<ProviderModelPurpose> purposes = java.util.EnumSet.noneOf(ProviderModelPurpose.class);
        for (String action : model.supportedActions().orElse(List.of())) {
            String normalized = action.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
            if (normalized.equals("generatecontent")) {
                purposes.add(ProviderModelPurpose.CHAT);
            }
            if (normalized.equals("embedcontent")
                    || normalized.equals("batchembedcontents")
                    || normalized.equals("predict")) {
                purposes.add(ProviderModelPurpose.EMBEDDING);
            }
        }
        return Set.copyOf(purposes);
    }

    private static OpenAiDiscoveryOptions openAiOptions(ProviderAdapterOptions options) {
        return switch (options) {
            case ProviderAdapterOptions.OpenAiCompatible openAi ->
                new OpenAiDiscoveryOptions(openAi.organization(), openAi.project());
            case ProviderAdapterOptions.OpenAiResponses responses ->
                new OpenAiDiscoveryOptions(responses.organization(), responses.project());
            case ProviderAdapterOptions.Anthropic ignored ->
                throw new IllegalArgumentException("Anthropic does not use OpenAI discovery options");
            case ProviderAdapterOptions.GoogleGenAi ignored ->
                throw new IllegalArgumentException("Google does not use OpenAI discovery options");
        };
    }

    private static ProviderAdapterOptions.GoogleGenAi googleOptions(ProviderAdapterOptions options) {
        if (options instanceof ProviderAdapterOptions.GoogleGenAi google) {
            return google;
        }
        throw new IllegalArgumentException("Google discovery requires its matching options");
    }

    private static DiscoveryPage bounded(Iterable<ProviderModelDiscoveryCandidate> source) {
        LinkedHashMap<String, ProviderModelDiscoveryCandidate> unique = new LinkedHashMap<>();
        for (ProviderModelDiscoveryCandidate candidate : source) {
            unique.putIfAbsent(candidate.modelId(), candidate);
            if (unique.size() > MAXIMUM_MODELS) {
                break;
            }
        }
        boolean truncated = unique.size() > MAXIMUM_MODELS;
        List<ProviderModelDiscoveryCandidate> values =
                unique.values().stream().limit(MAXIMUM_MODELS).toList();
        return new DiscoveryPage(values, truncated);
    }

    private static ProviderModelDiscoveryCandidate candidate(
            String modelId, String displayName, Set<ProviderModelPurpose> purposes) {
        return new ProviderModelDiscoveryCandidate(modelId, displayName, purposes, OptionalInt.empty());
    }

    static Duration discoveryTimeout(ProviderEndpoint endpoint) {
        return endpoint.spec().timeout().compareTo(MAXIMUM_TIMEOUT) < 0
                ? endpoint.spec().timeout()
                : MAXIMUM_TIMEOUT;
    }

    private static String requiredSecret(char[] secret) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("credential must not be empty");
        }
        return new String(secret);
    }

    private record DiscoveryPage(List<ProviderModelDiscoveryCandidate> candidates, boolean truncated) {
        private DiscoveryPage {
            candidates = List.copyOf(candidates);
        }
    }

    private record OpenAiDiscoveryOptions(
            java.util.Optional<String> organization, java.util.Optional<String> project) {}
}
