package com.javaclaw.server.persistence;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderConfigurationSource;
import com.javaclaw.api.ProviderConnectionSpec;
import com.javaclaw.api.ProviderCredentialChange;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPreviewRequest;
import com.javaclaw.model.CredentialMaterial;
import com.javaclaw.protocol.ProviderRpcContracts;

import static com.javaclaw.server.persistence.ProviderModelPreviewFixture.await;
import static com.javaclaw.server.persistence.ProviderModelPreviewFixture.identity;
import static com.javaclaw.server.persistence.ProviderModelPreviewFixture.request;
import static com.javaclaw.server.persistence.ProviderModelPreviewFixture.result;
import static com.javaclaw.server.persistence.ProviderModelPreviewFixture.source;
import static com.javaclaw.server.persistence.ProviderModelPreviewFixture.startIdentity;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderModelPreviewSourceTest {
    @TempDir
    Path directory;

    @Test
    void 保留密钥从精确来源解析材料且不改变已保存版本() throws Exception {
        AtomicReference<CredentialMaterial> borrowed = new AtomicReference<>();
        try (var fixture = new ProviderModelPreviewFixture(directory);
                var service = fixture.preview((request, material, token) -> {
                    borrowed.set(material.orElseThrow());
                    assertArrayEquals(
                            "saved-preview-key".toCharArray(), borrowed.get().copy());
                    return result(request);
                })) {
            var endpoint = bind(fixture);
            var request = source(endpoint, 1);
            var operation = service.start("owner", startIdentity("preview", request), request, () -> {
                throw new AssertionError("KEEP 不得解封客户端内容");
            });
            assertTrue(await(service, "owner", operation).result().isPresent());
            assertThrows(IllegalStateException.class, () -> borrowed.get().copy());
            assertEquals(List.of(endpoint), fixture.providers.listLatest());
            assertEquals(1, fixture.vault.status().credentialCount());
        }
    }

    @Test
    void 来源版本凭据版本归档与更换目标均拒绝旧密钥读取() {
        try (var fixture = new ProviderModelPreviewFixture(directory);
                var service = fixture.preview((request, material, token) -> result(request))) {
            var endpoint = bind(fixture);
            var staleCredential = source(endpoint, 0);
            assertThrows(
                    PersistenceException.class,
                    () -> service.start(
                            "owner", startIdentity("credential", staleCredential), staleCredential, () -> null));
            var connection = ProviderConnectionSpec.from(endpoint.spec());
            var changedAddress = new ProviderConnectionSpec(
                    connection.displayName(),
                    connection.adapter(),
                    Optional.of(URI.create("http://127.0.0.1:11435/v1")),
                    connection.authentication(),
                    connection.timeout(),
                    connection.maximumRetries(),
                    connection.options());
            var changed = new ProviderModelPreviewRequest(
                    "draft", 1, changedAddress, source(endpoint, 1).source(), ProviderCredentialChange.KEEP);
            assertThrows(
                    PersistenceException.class,
                    () -> service.start("owner", startIdentity("address", changed), changed, () -> null));
            var updated = fixture.providers.update(
                    identity("provider/update", "update", endpoint.revision(), endpoint.spec()),
                    endpoint.id(),
                    endpoint.spec(),
                    ProviderLifecycle.DISABLED);
            var stale = source(endpoint, 1);
            assertThrows(
                    PersistenceException.class,
                    () -> service.start("owner", startIdentity("provider", stale), stale, () -> null));
            var archived = fixture.providers.archive(
                    identity(
                            "provider/archive",
                            "archive",
                            updated.revision(),
                            new ProviderRpcContracts.ProviderArchivePayload(updated.id())),
                    updated.id());
            var archivedRequest = source(archived, 1);
            assertThrows(
                    PersistenceException.class,
                    () -> service.start(
                            "owner", startIdentity("archived", archivedRequest), archivedRequest, () -> null));
        }
    }

    @Test
    void 缺失来源缺失凭据及不匹配鉴权意图均在解封前拒绝() {
        try (var fixture = new ProviderModelPreviewFixture(directory);
                var service = fixture.preview((request, material, token) -> result(request))) {
            var key = request(ProviderAuthentication.API_KEY);
            var keepMissing = new ProviderModelPreviewRequest(
                    "draft", 1, key.connection(), Optional.empty(), ProviderCredentialChange.KEEP);
            assertThrows(
                    PersistenceException.class,
                    () -> service.start("owner", startIdentity("missing", keepMissing), keepMissing, () -> null));
            var endpoint = fixture.create(ProviderAuthentication.API_KEY);
            var keep = source(endpoint, 0);
            assertThrows(
                    PersistenceException.class,
                    () -> service.start("owner", startIdentity("unbound", keep), keep, () -> null));
            var clearApiKey = new ProviderModelPreviewRequest(
                    "draft", 1, key.connection(), Optional.empty(), ProviderCredentialChange.CLEAR);
            assertThrows(
                    PersistenceException.class,
                    () -> service.start("owner", startIdentity("clear", clearApiKey), clearApiKey, () -> null));
            var noAuth = new ProviderModelPreviewRequest(
                    "draft",
                    1,
                    ProviderModelPreviewFixture.connection(ProviderAuthentication.NONE),
                    Optional.of(new ProviderConfigurationSource(endpoint.id(), endpoint.revision(), 0)),
                    ProviderCredentialChange.REPLACE);
            assertThrows(
                    PersistenceException.class,
                    () -> service.start("owner", startIdentity("noauth", noAuth), noAuth, () -> null));
        }
    }

    @Test
    void 非法Utf8和空临时密钥拒绝并清零输入() {
        try (var fixture = new ProviderModelPreviewFixture(directory);
                var service = fixture.preview((request, material, token) -> result(request))) {
            var request = request(ProviderAuthentication.API_KEY);
            byte[] malformed = {(byte) 0xc3, (byte) 0x28};
            assertThrows(
                    PersistenceException.class,
                    () -> service.start("owner", startIdentity("invalid-utf8", request), request, () -> malformed));
            assertArrayEquals(new byte[malformed.length], malformed);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.start("owner", startIdentity("empty", request), request, () -> new byte[0]));
            assertEquals(0, fixture.vault.status().credentialCount());
        }
    }

    @Test
    void 无鉴权保留意图不要求来源且存在来源时仍校验版本() throws Exception {
        try (var fixture = new ProviderModelPreviewFixture(directory);
                var service = fixture.preview((request, material, token) -> {
                    assertTrue(material.isEmpty());
                    return result(request);
                })) {
            var connection = ProviderModelPreviewFixture.connection(ProviderAuthentication.NONE);
            var request = new ProviderModelPreviewRequest(
                    "draft", 1, connection, Optional.empty(), ProviderCredentialChange.KEEP);
            var operation = service.start("owner", startIdentity("new-no-auth", request), request, () -> {
                throw new AssertionError("无鉴权保留操作不能解封秘密");
            });
            assertTrue(await(service, "owner", operation).result().isPresent());
            assertTrue(fixture.providers.listLatest().isEmpty());
            var endpoint = fixture.create(ProviderAuthentication.NONE);
            var stale = new ProviderModelPreviewRequest(
                    "draft",
                    2,
                    connection,
                    Optional.of(new ProviderConfigurationSource(endpoint.id(), endpoint.revision() + 1, 0)),
                    ProviderCredentialChange.KEEP);
            assertThrows(
                    PersistenceException.class,
                    () -> service.start("owner", startIdentity("stale-no-auth", stale), stale, () -> null));
            var edit = new ProviderModelPreviewRequest(
                    "draft",
                    3,
                    connection,
                    Optional.of(new ProviderConfigurationSource(endpoint.id(), endpoint.revision(), 0)),
                    ProviderCredentialChange.KEEP);
            assertTrue(await(
                            service,
                            "owner",
                            service.start("owner", startIdentity("edit-no-auth", edit), edit, () -> null))
                    .result()
                    .isPresent());
        }
    }

    private static ProviderEndpoint bind(ProviderModelPreviewFixture fixture) {
        var shell = fixture.create(ProviderAuthentication.API_KEY);
        var service = new ProviderCredentialService(
                fixture.providers,
                fixture.vault.providerCredentials(),
                fixture.json,
                ProviderModelPreviewFixture.CLOCK);
        return service.set(
                        identity("provider/credential/set", "bind", shell.revision(), shell),
                        shell.id(),
                        shell.revision(),
                        0,
                        "saved-preview-key".getBytes(StandardCharsets.UTF_8))
                .provider();
    }
}
