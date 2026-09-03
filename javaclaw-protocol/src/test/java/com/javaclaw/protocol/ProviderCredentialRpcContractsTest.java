package com.javaclaw.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CredentialRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderCredentialRpcContractsTest {
    private static final String SET_METHOD = "provider/credential/set";
    private static final String CLEAR_METHOD = "provider/credential/clear";

    @Test
    void 复合命令对Provider与Credential分别携带精确Revision() {
        CanonicalJson json = new CanonicalJson();
        SealedSecret secret =
                new SealedSecret("session-1", ProviderCredentialRpcContracts.SET_PURPOSE, "AA", "AA", "AA");
        var set = new ProviderCredentialRpcContracts.SetPayload("provider-main", 3, 2, secret);
        var clear = new ProviderCredentialRpcContracts.ClearPayload(
                "provider-main", 4, new CredentialRef("provider", "credential-1"), 3);
        NegotiatedCapabilities none = new NegotiatedCapabilities(Set.of(), Set.of());

        assertEquals(set, json.decode(json.encode(set), ProviderCredentialRpcContracts.SetPayload.class));
        assertEquals(clear, json.decode(json.encode(clear), ProviderCredentialRpcContracts.ClearPayload.class));
        assertEquals(
                RpcMethodKind.COMMAND, MethodCatalog.require(SET_METHOD, none).kind());
        assertEquals(
                RpcMethodKind.COMMAND, MethodCatalog.require(CLEAR_METHOD, none).kind());
    }

    @Test
    void 复合命令拒绝错误用途Namespace和Revision() {
        SealedSecret wrongPurpose = new SealedSecret("session-1", "credential/provider/create", "AA", "AA", "AA");

        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderCredentialRpcContracts.SetPayload("provider-main", 1, 0, wrongPurpose));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderCredentialRpcContracts.SetPayload("provider-main", 0, 0, providerSecret()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderCredentialRpcContracts.ClearPayload(
                        "provider-main", 1, new CredentialRef("mcp", "credential-1"), 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProviderCredentialRpcContracts.ClearPayload(
                        "provider-main", 1, new CredentialRef("provider", "credential-1"), 0));
    }

    @Test
    void 方法目录逐方法引用严格ProviderCredentialSchema() throws Exception {
        CanonicalJson json = new CanonicalJson();
        String schema = read("/schema/provider-credential-v2.schema.json");
        String methods = read("/schema/methods-v2.json");

        json.parse(schema);
        assertFalse(schema.contains("\"roles\""));
        assertTrue(schema.contains("provider-profile-v2.schema.json#/$defs/provider"));
        assertTrue(methods.contains("provider-credential-v2.schema.json#/$defs/setCommand"));
        assertTrue(methods.contains("provider-credential-v2.schema.json#/$defs/clearCommand"));
        assertTrue(methods.contains("provider-credential-v2.schema.json#/$defs/bindingResult"));
        assertTrue(methods.contains("provider-credential-v2.schema.json#/$defs/clearResult"));
    }

    private static SealedSecret providerSecret() {
        return new SealedSecret("session-1", ProviderCredentialRpcContracts.SET_PURPOSE, "AA", "AA", "AA");
    }

    private static String read(String resource) throws IOException {
        try (var stream = ProviderCredentialRpcContractsTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("missing test resource: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
