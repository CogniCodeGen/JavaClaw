package com.javaclaw.model;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import com.javaclaw.runtime.ModelInvocation;

import static com.javaclaw.model.ModelAdapterTestFixtures.TOOL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringAiModelFactoryTest {
    private static final URI LOCAL_ENDPOINT = URI.create("http://127.0.0.1:1");

    @Test
    void createsOpenAiEndpointAndInvocationOptionsWithoutCallingNetwork() throws Exception {
        SpringAiEndpoint endpoint = create(SpringAiProvider.OPENAI_COMPATIBLE);
        try {
            assertInstanceOf(OpenAiChatModel.class, endpoint.model());
            OpenAiChatOptions options = assertInstanceOf(
                    OpenAiChatOptions.class, endpoint.options().create(largeInvocation(endpoint.id())));
            assertEquals("provider-model", options.getModel());
            assertEquals(Integer.MAX_VALUE, options.getMaxCompletionTokens());
            assertEquals(Boolean.TRUE, options.getStreamOptions().includeUsage());
            assertEquals(
                    "read_file",
                    options.getToolCallbacks().getFirst().getToolDefinition().name());
            assertEndpointCapabilities(endpoint);
        } finally {
            endpoint.resources().close();
        }
    }

    @Test
    void createsAnthropicEndpointAndInvocationOptionsWithoutCallingNetwork() throws Exception {
        SpringAiEndpoint endpoint = create(SpringAiProvider.ANTHROPIC);
        try {
            assertInstanceOf(AnthropicChatModel.class, endpoint.model());
            AnthropicChatOptions options = assertInstanceOf(
                    AnthropicChatOptions.class, endpoint.options().create(largeInvocation(endpoint.id())));
            assertEquals("provider-model", options.getModel());
            assertEquals(Integer.MAX_VALUE, options.getMaxTokens());
            assertEquals(
                    "read_file",
                    options.getToolCallbacks().getFirst().getToolDefinition().name());
            assertEndpointCapabilities(endpoint);
        } finally {
            endpoint.resources().close();
        }
    }

    @Test
    void createsGoogleEndpointAndInvocationOptionsWithoutCallingNetwork() throws Exception {
        SpringAiEndpoint endpoint = create(SpringAiProvider.GOOGLE_GENAI);
        try {
            assertInstanceOf(GoogleGenAiChatModel.class, endpoint.model());
            GoogleGenAiChatOptions options = assertInstanceOf(
                    GoogleGenAiChatOptions.class, endpoint.options().create(largeInvocation(endpoint.id())));
            assertEquals("provider-model", options.getModel());
            assertEquals(Integer.MAX_VALUE, options.getMaxOutputTokens());
            assertEquals(Boolean.TRUE, options.getIncludeExtendedUsageMetadata());
            assertEquals(
                    "read_file",
                    options.getToolCallbacks().getFirst().getToolDefinition().name());
            assertEndpointCapabilities(endpoint);
        } finally {
            endpoint.resources().close();
        }
    }

    @Test
    void rejectsMissingProviderSecretBeforeConstructingClient() {
        SpringAiModelFactory factory = new SpringAiModelFactory();
        SpringAiEndpointConfig config = config(SpringAiProvider.OPENAI_COMPATIBLE);

        assertThrows(IllegalArgumentException.class, () -> factory.create(config, null));
        assertThrows(IllegalArgumentException.class, () -> factory.create(config, new char[0]));
    }

    private static SpringAiEndpoint create(SpringAiProvider provider) {
        char[] secret = "test-secret".toCharArray();
        try {
            return new SpringAiModelFactory().create(config(provider), secret);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private static SpringAiEndpointConfig config(SpringAiProvider provider) {
        return new SpringAiEndpointConfig(
                "endpoint-" + provider.name().toLowerCase(),
                provider,
                "provider-model",
                Optional.of(LOCAL_ENDPOINT),
                Duration.ofMillis(500),
                0);
    }

    private static ModelInvocation largeInvocation(String endpointId) {
        return new ModelInvocation(endpointId, "", List.of(), List.of(TOOL), (long) Integer.MAX_VALUE + 100L);
    }

    private static void assertEndpointCapabilities(SpringAiEndpoint endpoint) {
        assertTrue(endpoint.capabilities().streaming());
        assertTrue(endpoint.capabilities().toolCalls());
        assertTrue(endpoint.capabilities().structuredOutput());
        assertTrue(endpoint.capabilities().images());
    }
}
