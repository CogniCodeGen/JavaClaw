package com.javaclaw.protocol;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpointSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderProfileSchemaContractTest {
    private static final CanonicalJson JSON = new CanonicalJson();

    @Test
    void Provider地址Schema与Java领域边界保持一致() throws Exception {
        List<UriCase> cases = List.of(
                valid(ProviderAdapter.OPENAI_COMPATIBLE, ProviderAuthentication.API_KEY, "https://api.example.test/v1"),
                valid(ProviderAdapter.OPENAI_COMPATIBLE, ProviderAuthentication.API_KEY, "HTTP://localhost:1234/v1"),
                valid(ProviderAdapter.OPENAI_COMPATIBLE, ProviderAuthentication.API_KEY, "http://127.255.0.1/v1"),
                valid(ProviderAdapter.OPENAI_COMPATIBLE, ProviderAuthentication.API_KEY, "http://[::1]:1234/v1"),
                valid(ProviderAdapter.OPENAI_COMPATIBLE, ProviderAuthentication.NONE, "http://10.0.0.5/v1"),
                valid(
                        ProviderAdapter.GOOGLE_GENAI,
                        ProviderAuthentication.API_KEY,
                        "https://generativelanguage.googleapis.com"),
                invalid(ProviderAdapter.OPENAI_COMPATIBLE, ProviderAuthentication.API_KEY, "http://10.0.0.5/v1"),
                invalid(ProviderAdapter.OPENAI_COMPATIBLE, ProviderAuthentication.API_KEY, "http://127.256.0.1/v1"),
                invalid(ProviderAdapter.ANTHROPIC, ProviderAuthentication.NONE, "https://api.anthropic.com"),
                invalid(ProviderAdapter.OPENAI_COMPATIBLE, ProviderAuthentication.API_KEY, "ftp://api.example.test/v1"),
                invalid(
                        ProviderAdapter.OPENAI_COMPATIBLE,
                        ProviderAuthentication.API_KEY,
                        "https://key@api.example.test/v1"),
                invalid(
                        ProviderAdapter.OPENAI_COMPATIBLE,
                        ProviderAuthentication.API_KEY,
                        "https://api.example.test/v1?q=x"),
                invalid(
                        ProviderAdapter.OPENAI_COMPATIBLE,
                        ProviderAuthentication.API_KEY,
                        "https://api.example.test/v1#x"),
                invalid(
                        ProviderAdapter.OPENAI_COMPATIBLE,
                        ProviderAuthentication.API_KEY,
                        "https://api.example.test/a/../v1"),
                invalid(
                        ProviderAdapter.OPENAI_COMPATIBLE,
                        ProviderAuthentication.API_KEY,
                        "https://api.example.test/models"),
                invalid(ProviderAdapter.GOOGLE_GENAI, ProviderAuthentication.API_KEY, "https://example.test/v1beta"),
                invalid(
                        ProviderAdapter.GOOGLE_GENAI,
                        ProviderAuthentication.API_KEY,
                        "https://example.test/models/gemini:generateContent"));

        JsonNode document = schema();
        for (UriCase value : cases) {
            assertEquals(value.valid(), schemaAccepts(document, value), "Schema URI result: " + value.uri());
            assertEquals(value.valid(), domainAccepts(value), "Java URI result: " + value.uri());
        }
    }

    @Test
    void Provider模型目录声明结构唯一并在Decode边界按ModelId唯一() throws Exception {
        JsonNode document = schema();
        JsonNode models = document.at("/$defs/providerSpec/properties/models");

        assertTrue(models.path("uniqueItems").booleanValue());
        assertTrue(models.path("$comment").textValue().contains("modelId"));
        assertTrue(references(document.at("/$defs/providerSpec")).contains("#/$defs/httpApiRootUri"));
        assertTrue(references(document.at("/$defs/providerSpec")).contains("#/$defs/apiKeyApiRootUri"));
        assertTrue(references(document.at("/$defs/providerSpec")).contains("#/$defs/googleApiRootUri"));

        assertThrows(
                ProtocolException.class,
                () -> JSON.decode(JSON.parse(duplicateModelSpec()), ProviderEndpointSpec.class));
    }

    private static boolean schemaAccepts(JsonNode document, UriCase value) {
        boolean accepted = matches(document, document.at("/$defs/httpApiRootUri"), value.uri());
        if (value.authentication() == ProviderAuthentication.API_KEY) {
            accepted &= matches(document, document.at("/$defs/apiKeyApiRootUri"), value.uri());
        }
        if (value.adapter() == ProviderAdapter.GOOGLE_GENAI) {
            accepted &= matches(document, document.at("/$defs/googleApiRootUri"), value.uri());
        }
        if (value.authentication() == ProviderAuthentication.NONE) {
            accepted &= value.adapter() == ProviderAdapter.OPENAI_COMPATIBLE;
        }
        return accepted;
    }

    private static boolean domainAccepts(UriCase value) {
        try {
            new ProviderEndpointSpec(
                    "Provider",
                    value.adapter(),
                    Optional.of(URI.create(value.uri())),
                    value.authentication(),
                    List.of(),
                    Optional.empty(),
                    Duration.ofSeconds(30),
                    0,
                    ProviderAdapterOptions.defaults(value.adapter()));
            return true;
        } catch (IllegalArgumentException rejected) {
            return false;
        }
    }

    private static boolean matches(JsonNode document, JsonNode rule, String value) {
        if (rule.has("$ref")
                && !matches(document, document.at(rule.path("$ref").textValue().substring(1)), value)) {
            return false;
        }
        if (rule.has("maxLength") && value.length() > rule.path("maxLength").intValue()) {
            return false;
        }
        if (rule.has("pattern")
                && !Pattern.compile(rule.path("pattern").textValue())
                        .matcher(value)
                        .find()) {
            return false;
        }
        if (rule.has("not") && matches(document, rule.path("not"), value)) {
            return false;
        }
        if (rule.has("allOf") && !allMatch(document, rule.path("allOf"), value)) {
            return false;
        }
        return !rule.has("anyOf") || anyMatch(document, rule.path("anyOf"), value);
    }

    private static boolean allMatch(JsonNode document, JsonNode rules, String value) {
        for (JsonNode rule : rules) {
            if (!matches(document, rule, value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean anyMatch(JsonNode document, JsonNode rules, String value) {
        for (JsonNode rule : rules) {
            if (matches(document, rule, value)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> references(JsonNode root) {
        HashSet<String> result = new HashSet<>();
        root.findValues("$ref").forEach(value -> result.add(value.textValue()));
        return Set.copyOf(result);
    }

    private static JsonNode schema() throws IOException {
        try (var input = ProviderProfileSchemaContractTest.class.getResourceAsStream(
                "/schema/provider-profile-v2.schema.json")) {
            if (input == null) {
                throw new IOException("provider-profile-v2 schema not found");
            }
            return JSON.mapper().readTree(input);
        }
    }

    private static UriCase valid(ProviderAdapter adapter, ProviderAuthentication authentication, String uri) {
        return new UriCase(adapter, authentication, uri, true);
    }

    private static UriCase invalid(ProviderAdapter adapter, ProviderAuthentication authentication, String uri) {
        return new UriCase(adapter, authentication, uri, false);
    }

    private static String duplicateModelSpec() {
        return """
                {
                  "displayName":"Provider",
                  "adapter":"OPENAI_COMPATIBLE",
                  "baseUri":"https://api.example.test/v1",
                  "authentication":"API_KEY",
                  "models":[
                    {"modelId":"same","displayName":"Chat","purposes":["CHAT"],"embeddingDimensions":null},
                    {"modelId":"same","displayName":"Embedding","purposes":["EMBEDDING"],"embeddingDimensions":1536}
                  ],
                  "credential":null,
                  "timeout":"PT30S",
                  "maximumRetries":0,
                  "options":{
                    "adapter":"OPENAI_COMPATIBLE",
                    "organization":null,
                    "project":null,
                    "apiVersion":null,
                    "reasoningSummary":"AUTO"
                  }
                }
                """;
    }

    private record UriCase(ProviderAdapter adapter, ProviderAuthentication authentication, String uri, boolean valid) {}
}
