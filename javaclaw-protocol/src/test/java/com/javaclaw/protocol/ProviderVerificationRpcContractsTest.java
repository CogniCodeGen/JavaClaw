package com.javaclaw.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderVerificationRpcContractsTest {
    private static final ProviderRef PROVIDER = new ProviderRef("provider-main", 2, "test-model");

    @Test
    void 双重危险确认必须同时精确匹配() {
        ProviderVerificationRpcContracts.VerifyPayload payload = new ProviderVerificationRpcContracts.VerifyPayload(
                PROVIDER, ProviderModelPurpose.CHAT, true, ProviderVerificationRpcContracts.BILLING_CONFIRMATION);

        assertEquals(PROVIDER, payload.provider());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderVerificationRpcContracts.VerifyPayload(
                        PROVIDER,
                        ProviderModelPurpose.CHAT,
                        false,
                        ProviderVerificationRpcContracts.BILLING_CONFIRMATION));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderVerificationRpcContracts.VerifyPayload(
                        PROVIDER, ProviderModelPurpose.CHAT, true, "确认"));
    }

    @Test
    void 规范Json不会引入额外确认字段() {
        CanonicalJson json = new CanonicalJson();
        ProviderVerificationRpcContracts.VerifyPayload payload = new ProviderVerificationRpcContracts.VerifyPayload(
                PROVIDER, ProviderModelPurpose.EMBEDDING, true, ProviderVerificationRpcContracts.BILLING_CONFIRMATION);

        assertEquals(payload, json.decode(json.encode(payload), ProviderVerificationRpcContracts.VerifyPayload.class));
        assertEquals(
                RpcMethodKind.COMMAND,
                MethodCatalog.require(
                                ProviderVerificationRpcContracts.METHOD,
                                new NegotiatedCapabilities(java.util.Set.of(), java.util.Set.of()))
                        .kind());
    }

    @Test
    void 方法目录引用严格计费验证Schema() throws Exception {
        CanonicalJson json = new CanonicalJson();
        String schema = read("/schema/provider-verification-v3.schema.json");
        String methods = read("/schema/methods-v3.json");

        json.parse(schema);
        assertFalse(schema.contains("\"roles\""));
        assertTrue(schema.contains("provider-v3.schema.json#/$defs/providerCapabilities"));
        assertTrue(schema.contains("\"billingConfirmed\": {\"const\": true}"));
        assertTrue(schema.contains(ProviderVerificationRpcContracts.BILLING_CONFIRMATION));
        assertTrue(methods.contains("provider-verification-v3.schema.json#/$defs/verifyCommand"));
        assertTrue(methods.contains("provider-verification-v3.schema.json#/$defs/verificationResult"));
    }

    private static String read(String resource) throws IOException {
        try (var stream = ProviderVerificationRpcContractsTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("missing test resource: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
