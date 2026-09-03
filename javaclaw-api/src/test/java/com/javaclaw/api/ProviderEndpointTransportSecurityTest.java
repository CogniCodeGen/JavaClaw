package com.javaclaw.api;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderEndpointTransportSecurityTest {
    @Test
    void APIKey端点允许HTTPS和显式回环HTTP() {
        List<String> allowed = List.of(
                "https://models.example.test/v1",
                "http://localhost:8080/v1",
                "http://LOCALHOST:8080/v1",
                "http://127.0.0.1:8080/v1",
                "http://127.255.255.254:8080/v1",
                "http://[::1]:8080/v1");

        allowed.forEach(address -> assertDoesNotThrow(() -> apiKeySpec(address)));
    }

    @Test
    void APIKey端点拒绝非回环HTTP和含用户信息的地址() {
        List<String> rejected = List.of(
                "http://models.example.test/v1",
                "http://localhost.example.test/v1",
                "http://127.0.0.1.example.test/v1",
                "http://126.255.255.255/v1",
                "http://128.0.0.1/v1",
                "http://127.0.0.256/v1",
                "http://127.000.000.001/v1",
                "http://127.1/v1",
                "http://[::2]/v1",
                "http://key@localhost/v1");

        rejected.forEach(address -> assertThrows(IllegalArgumentException.class, () -> apiKeySpec(address)));
    }

    @Test
    void 无鉴权兼容端点仍允许HTTP和HTTPS() {
        assertEquals(
                URI.create("http://models.example.test/v1"),
                noAuthenticationSpec("http://models.example.test/v1").baseUri().orElseThrow());
        assertEquals(
                URI.create("https://models.example.test/v1"),
                noAuthenticationSpec("https://models.example.test/v1").baseUri().orElseThrow());
    }

    @Test
    void 模型标识在API上限内可表达而超限被拒绝() {
        assertEquals(501, model("m".repeat(501)).modelId().length());
        assertEquals(1_000, model("m".repeat(1_000)).modelId().length());
        assertThrows(IllegalArgumentException.class, () -> model("m".repeat(1_001)));
        assertThrows(IllegalArgumentException.class, () -> new ProviderRef("provider", 1, "m".repeat(1_001)));
    }

    private static ProviderEndpointSpec apiKeySpec(String address) {
        return spec(ProviderAuthentication.API_KEY, address);
    }

    private static ProviderEndpointSpec noAuthenticationSpec(String address) {
        return spec(ProviderAuthentication.NONE, address);
    }

    private static ProviderEndpointSpec spec(ProviderAuthentication authentication, String address) {
        return new ProviderEndpointSpec(
                "Provider",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(URI.create(address)),
                authentication,
                List.of(model("chat")),
                Optional.empty(),
                Duration.ofSeconds(30),
                1,
                ProviderAdapterOptions.defaults(ProviderAdapter.OPENAI_COMPATIBLE));
    }

    private static ProviderModelSpec model(String modelId) {
        return new ProviderModelSpec(modelId, modelId, Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty());
    }
}
