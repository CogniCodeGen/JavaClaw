package com.javaclaw.model;

import java.util.List;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Model;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.openai.client.OpenAIClient;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelInvocation;

/** 构造三家 Spring AI ChatModel，并把具体 SDK 类型封闭在本模块。 */
final class SpringAiModelFactory {
    SpringAiEndpoint create(SpringAiEndpointConfig config, char[] apiKey) {
        return switch (config.provider()) {
            case OPENAI_COMPATIBLE -> openAi(config, apiKey);
            case ANTHROPIC -> anthropic(config, requiredSecret(apiKey));
            case GOOGLE_GENAI -> google(config, requiredSecret(apiKey));
        };
    }

    private SpringAiEndpoint openAi(SpringAiEndpointConfig config, char[] apiKey) {
        OpenAIClient client = OpenAiSdkClientFactory.create(
                config.baseUri(),
                config.authentication(),
                config.organization(),
                config.project(),
                config.timeout(),
                config.maximumRetries(),
                apiKey);
        OpenAiChatOptions defaults =
                OpenAiChatOptions.builder().model(config.model()).build();
        ChatModel model = OpenAiChatModel.builder()
                .openAiClient(client)
                .openAiClientAsync(client.async())
                .options(defaults)
                .build();
        return endpoint(config, model, invocation -> openAiOptions(config, invocation), client::close);
    }

    private SpringAiEndpoint anthropic(SpringAiEndpointConfig config, String apiKey) {
        AnthropicOkHttpClient.Builder clientBuilder = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(config.timeout())
                .maxRetries(config.maximumRetries());
        config.baseUri().ifPresent(uri -> clientBuilder.baseUrl(uri.toString()));
        AnthropicClient client = clientBuilder.build();
        AnthropicChatOptions defaults = AnthropicChatOptions.builder()
                .model(Model.of(config.model()))
                .maxTokens(1)
                .build();
        ChatModel model = AnthropicChatModel.builder()
                .anthropicClient(client)
                .anthropicClientAsync(client.async())
                .options(defaults)
                .build();
        return endpoint(config, model, invocation -> anthropicOptions(config, invocation), client::close);
    }

    private SpringAiEndpoint google(SpringAiEndpointConfig config, String apiKey) {
        HttpOptions.Builder http =
                HttpOptions.builder().timeout(Math.toIntExact(config.timeout().toMillis()));
        config.baseUri().ifPresent(uri -> http.baseUrl(uri.toString()));
        config.apiVersion().ifPresent(http::apiVersion);
        Client client =
                Client.builder().apiKey(apiKey).httpOptions(http.build()).build();
        GoogleGenAiChatOptions defaults = GoogleGenAiChatOptions.builder()
                .model(config.model())
                .maxOutputTokens(1)
                .build();
        ChatModel model = GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .options(defaults)
                .build();
        return endpoint(config, model, invocation -> googleOptions(config, invocation), client);
    }

    private SpringAiEndpoint endpoint(
            SpringAiEndpointConfig config,
            ChatModel model,
            SpringAiEndpoint.OptionsFactory options,
            AutoCloseable resources) {
        ModelCapabilities capabilities = new ModelCapabilities(true, true, true, true, false, false, false);
        return new SpringAiEndpoint(config.endpointId(), model, capabilities, options, resources);
    }

    private OpenAiChatOptions openAiOptions(SpringAiEndpointConfig config, ModelInvocation invocation) {
        var builder = OpenAiChatOptions.builder()
                .model(config.model())
                .maxCompletionTokens(tokens(invocation))
                .streamUsage(true)
                .toolCallbacks(callbacks(invocation));
        invocation
                .reasoning()
                .ifPresent(preference -> builder.reasoningEffort(AdapterReasoningMapping.openAiCompatible(preference)));
        return builder.build();
    }

    private AnthropicChatOptions anthropicOptions(SpringAiEndpointConfig config, ModelInvocation invocation) {
        var builder = AnthropicChatOptions.builder()
                .model(Model.of(config.model()))
                .maxTokens(tokens(invocation))
                .toolCallbacks(callbacks(invocation));
        invocation.reasoning().ifPresent(preference -> AdapterReasoningMapping.anthropic(builder, preference));
        return builder.build();
    }

    private GoogleGenAiChatOptions googleOptions(SpringAiEndpointConfig config, ModelInvocation invocation) {
        var builder = GoogleGenAiChatOptions.builder()
                .model(config.model())
                .maxOutputTokens(tokens(invocation))
                .includeExtendedUsageMetadata(true)
                .toolCallbacks(callbacks(invocation));
        invocation.reasoning().ifPresent(preference -> AdapterReasoningMapping.google(builder, preference));
        return builder.build();
    }

    private List<ToolCallback> callbacks(ModelInvocation invocation) {
        return invocation.tools().stream()
                .map(DefinitionOnlyToolCallback::new)
                .map(ToolCallback.class::cast)
                .toList();
    }

    private int tokens(ModelInvocation invocation) {
        return Math.toIntExact(Math.min(Integer.MAX_VALUE, invocation.maximumOutputTokens()));
    }

    private String requiredSecret(char[] apiKey) {
        if (apiKey == null || apiKey.length == 0) {
            throw new IllegalArgumentException("apiKey must not be empty");
        }
        return new String(apiKey);
    }
}
