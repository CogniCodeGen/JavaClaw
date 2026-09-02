package com.javaclaw.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialRpcContractsTest {
    @Test
    void 密文payload引用当前会话且可规范JSON往返() {
        CanonicalJson json = new CanonicalJson();
        CredentialRef reference = new CredentialRef("provider", "credential-1");
        SealedSecret secret = new SealedSecret("session-1", "credential/provider/rotate", "AA", "AA", "AA");
        CredentialRpcContracts.RotatePayload payload = new CredentialRpcContracts.RotatePayload(reference, secret);

        assertEquals(payload, json.decode(json.encode(payload), CredentialRpcContracts.RotatePayload.class));
        assertEquals(reference, new CredentialRpcContracts.ClearPayload(reference).reference());
        assertTrue(new CredentialRpcContracts.ReadResult(java.util.Optional.empty())
                .credential()
                .isEmpty());
        CredentialMetadata metadata = new CredentialMetadata(reference, 1, Instant.parse("2026-09-01T00:00:00Z"));
        assertEquals(
                java.util.List.of(metadata),
                new CredentialRpcContracts.ListResult(java.util.List.of(metadata)).credentials());
        assertEquals("site", new CredentialRpcContracts.ListPayload(" site ").namespace());
    }

    @Test
    void Vault重置只接受精确危险确认() {
        assertEquals("RESET VAULT", new CredentialRpcContracts.ResetPayload("RESET VAULT").confirmation());
        assertThrows(IllegalArgumentException.class, () -> new CredentialRpcContracts.ResetPayload("reset vault"));
        assertThrows(NullPointerException.class, () -> new CredentialRpcContracts.ResetPayload(null));
    }

    @Test
    void stable能力目录只宣告ViewSchemaV2() {
        assertTrue(StableCapabilities.all().contains("extension.view-schema-v2"));
        assertTrue(StableCapabilities.all().contains("core.secret-vault"));
        assertTrue(StableCapabilities.all().stream().noneMatch("extension.view-schema"::equals));
        assertThrows(
                UnsupportedOperationException.class,
                () -> StableCapabilities.all().add("unsupported-capability"));
    }

    @Test
    void Vault和Workspace方法都声明逐方法Schema() throws Exception {
        CanonicalJson json = new CanonicalJson();
        String credential = read("/schema/credential-v2.schema.json");
        String workspace = read("/schema/workspace-v2.schema.json");
        String methods = read("/schema/methods-v2.json");

        json.parse(credential);
        json.parse(workspace);
        assertTrue(methods.contains("credential-v2.schema.json#/$defs/resetCommand"));
        assertTrue(methods.contains("credential-v2.schema.json#/$defs/listResult"));
        assertTrue(methods.contains("workspace-v2.schema.json#/$defs/archiveCommand"));
    }

    private static String read(String resource) throws IOException {
        try (var stream = CredentialRpcContractsTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("missing test resource: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
