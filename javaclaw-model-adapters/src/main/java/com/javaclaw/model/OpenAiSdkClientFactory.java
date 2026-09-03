package com.javaclaw.model;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.ClientOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.ProviderAuthentication;

/** 统一构造 OpenAI SDK 客户端，并显式处理兼容端点的无鉴权传输。 */
final class OpenAiSdkClientFactory {
    private static final String NO_AUTHENTICATION_PLACEHOLDER = "javaclaw-no-authentication";

    private OpenAiSdkClientFactory() {}

    static OpenAIClient create(
            Optional<URI> baseUri,
            ProviderAuthentication authentication,
            Optional<String> organization,
            Optional<String> project,
            Duration timeout,
            int maximumRetries,
            char[] secret) {
        Objects.requireNonNull(baseUri, "baseUri");
        Objects.requireNonNull(authentication, "authentication");
        Objects.requireNonNull(organization, "organization");
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(timeout, "timeout");
        if (authentication == ProviderAuthentication.NONE) {
            return noAuthentication(baseUri, organization, project, timeout, maximumRetries);
        }
        return apiKey(baseUri, organization, project, timeout, maximumRetries, secret);
    }

    static OpenAIClient discovery(
            Optional<URI> baseUri,
            ProviderAuthentication authentication,
            Optional<String> organization,
            Optional<String> project,
            Duration timeout,
            char[] secret,
            CancellationToken cancellation) {
        String apiKey =
                authentication == ProviderAuthentication.NONE ? NO_AUTHENTICATION_PLACEHOLDER : requiredSecret(secret);
        ClientOptions.Builder options = ClientOptions.builder()
                .httpClient(DiscoveryHttpClients.openAi(
                        timeout, authentication == ProviderAuthentication.NONE, cancellation))
                .apiKey(apiKey)
                .timeout(timeout)
                .maxRetries(0);
        baseUri.ifPresent(uri -> options.baseUrl(uri.toString()));
        organization.ifPresent(options::organization);
        project.ifPresent(options::project);
        return new OpenAIClientImpl(options.build());
    }

    private static OpenAIClient apiKey(
            Optional<URI> baseUri,
            Optional<String> organization,
            Optional<String> project,
            Duration timeout,
            int maximumRetries,
            char[] secret) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("apiKey must not be empty");
        }
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                .apiKey(new String(secret))
                .timeout(timeout)
                .maxRetries(maximumRetries);
        baseUri.ifPresent(uri -> builder.baseUrl(uri.toString()));
        organization.ifPresent(builder::organization);
        project.ifPresent(builder::project);
        return builder.build();
    }

    private static String requiredSecret(char[] secret) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("apiKey must not be empty");
        }
        return new String(secret);
    }

    private static OpenAIClient noAuthentication(
            Optional<URI> baseUri,
            Optional<String> organization,
            Optional<String> project,
            Duration timeout,
            int maximumRetries) {
        String endpoint = baseUri.orElseThrow(
                        () -> new IllegalArgumentException("NONE authentication requires a custom baseUri"))
                .toString();
        SpringAiOpenAiHttpClient http = SpringAiOpenAiHttpClient.builder()
                .timeout(timeout)
                .interceptor(chain -> chain.proceed(chain.request()
                        .newBuilder()
                        .removeHeader("Authorization")
                        .build()))
                .build();
        ClientOptions.Builder options = ClientOptions.builder()
                .httpClient(http)
                .apiKey(NO_AUTHENTICATION_PLACEHOLDER)
                .baseUrl(endpoint)
                .timeout(timeout)
                .maxRetries(maximumRetries);
        organization.ifPresent(options::organization);
        project.ifPresent(options::project);
        return new OpenAIClientImpl(options.build());
    }
}
