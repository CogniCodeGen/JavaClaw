package com.javaclaw.server.extension.thirdparty;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.BrokerRequest;
import com.javaclaw.api.BrokerResponse;
import com.javaclaw.api.CancellationSource;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.NetworkBroker;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.persistence.H2Database;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThirdPartyWorkerActionHandlerTest {
    private static final Instant NOW = Instant.parse("2026-09-01T04:30:00Z");

    @TempDir
    java.nio.file.Path temporaryDirectory;

    private CanonicalJson json;
    private InstalledThirdPartyBundle bundle;
    private ThirdPartyWorkerActionHandler actions;
    private StubNetwork network;

    @BeforeEach
    void initialize() throws Exception {
        json = new CanonicalJson();
        var keys = ThirdPartyBundleTestFixtures.keyPair();
        H2Database database = new H2Database(temporaryDirectory.resolve("data-v5"));
        database.initialize();
        ThirdPartyBundleDirectories directories =
                new ThirdPartyBundleDirectories(database.dataRoot(), Clock.fixed(NOW, ZoneOffset.UTC));
        ThirdPartyBundleArchive archive =
                new ThirdPartyBundleArchive(json, ThirdPartyBundleTestFixtures.trustedKeys(keys), directories);
        bundle = new ThirdPartyBundleCompiler(json)
                .compile(
                        archive.stage(ThirdPartyBundleTestFixtures.attachment(
                                ThirdPartyBundleTestFixtures.archive(temporaryDirectory, json, keys))),
                        1);
        network = new StubNetwork();
        actions = new ThirdPartyWorkerActionHandler(database, json, Clock.fixed(NOW, ZoneOffset.UTC), network);
    }

    @Test
    void document动作支持乐观锁写入读取和稳定冲突码() {
        var created = execute(put("save", "state", 0, json.parse("{\"value\":1}")));
        var read = execute(action("read", ThirdPartyWorkerProtocol.ActionKind.DOCUMENT_GET, Map.of("key", "state")));
        var conflict = execute(put("stale", "state", 0, json.parse("{\"value\":2}")));

        assertTrue(created.ok());
        assertEquals(1, json.decode(created.payload(), RevisionResult.class).revision());
        DocumentResult document = json.decode(read.payload(), DocumentResult.class);
        assertTrue(document.present());
        assertEquals(1, document.revision());
        assertEquals("{\"value\":1}", document.payload().orElseThrow().json());
        assertFalse(conflict.ok());
        assertEquals("REVISION_CONFLICT", conflict.error().orElseThrow().code());
    }

    @Test
    void blob动作校验Base64声明大小并返回内容摘要() throws Exception {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        var stored = execute(action(
                "blob",
                ThirdPartyWorkerProtocol.ActionKind.BLOB_PUT,
                Map.of(
                        "mediaType",
                        "text/plain",
                        "sizeBytes",
                        content.length,
                        "contentBase64",
                        Base64.getEncoder().encodeToString(content))));
        var invalidBase64 = execute(action(
                "invalid",
                ThirdPartyWorkerProtocol.ActionKind.BLOB_PUT,
                Map.of("mediaType", "text/plain", "sizeBytes", 1, "contentBase64", "*")));
        var wrongSize = execute(action(
                "size",
                ThirdPartyWorkerProtocol.ActionKind.BLOB_PUT,
                Map.of(
                        "mediaType",
                        "text/plain",
                        "sizeBytes",
                        4,
                        "contentBase64",
                        Base64.getEncoder().encodeToString(content))));

        assertTrue(stored.ok());
        assertEquals(
                java.util.HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(content)),
                json.decode(stored.payload(), BlobResult.class).digest());
        assertInvalidArgument(invalidBase64);
        assertInvalidArgument(wrongSize);
    }

    @Test
    void network动作只经HostBroker并返回稳定失败码() {
        PermissionProfile permission = permission();
        CancellationSource cancellation = new CancellationSource();
        BrokerRequest request = new BrokerRequest(
                URI.create("https://example.com/value"),
                "GET",
                Map.of("accept", List.of("application/json")),
                new byte[0],
                128,
                Duration.ofSeconds(2));
        network.response = new BrokerResponse(
                200, Map.of("content-type", List.of("application/json")), "ok".getBytes(StandardCharsets.UTF_8), false);

        var success = actions.execute(
                        bundle,
                        permission,
                        cancellation,
                        List.of(action("network", ThirdPartyWorkerProtocol.ActionKind.NETWORK_EXCHANGE, request)))
                .getFirst();
        BrokerResponse decoded = json.decode(success.payload(), BrokerResponse.class);

        assertTrue(success.ok());
        assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8), decoded.body());
        assertEquals(request.uri(), network.request.uri());
        assertEquals(request.method(), network.request.method());
        assertEquals(request.headers(), network.request.headers());
        assertArrayEquals(request.body(), network.request.body());
        assertSame(permission, network.permission);
        assertSame(cancellation, network.cancellation);

        network.failure = new SecurityException("secret target details");
        var denied = execute(action("denied", ThirdPartyWorkerProtocol.ActionKind.NETWORK_EXCHANGE, request));
        assertEquals("PERMISSION_DENIED", denied.error().orElseThrow().code());
        assertFalse(denied.error().orElseThrow().message().contains("secret"));

        network.failure = new IOException("secret address details");
        var failed = execute(action("failed", ThirdPartyWorkerProtocol.ActionKind.NETWORK_EXCHANGE, request));
        assertEquals("NETWORK_FAILED", failed.error().orElseThrow().code());
        assertFalse(failed.error().orElseThrow().message().contains("secret"));
    }

    @Test
    void handler拒绝空批次重复标识超量动作并规范化错误参数() {
        assertThrows(
                IllegalArgumentException.class,
                () -> actions.execute(bundle, permission(), new CancellationSource(), List.of()));
        var duplicate = action("same", ThirdPartyWorkerProtocol.ActionKind.DOCUMENT_GET, Map.of("key", "a"));
        assertThrows(
                IllegalArgumentException.class,
                () -> actions.execute(bundle, permission(), new CancellationSource(), List.of(duplicate, duplicate)));
        ArrayList<ThirdPartyWorkerProtocol.Action> excessive = new ArrayList<>();
        for (int index = 0; index < 17; index++) {
            excessive.add(
                    action("read-" + index, ThirdPartyWorkerProtocol.ActionKind.DOCUMENT_GET, Map.of("key", "a")));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> actions.execute(bundle, permission(), new CancellationSource(), excessive));

        var malformed = execute(new ThirdPartyWorkerProtocol.Action(
                "malformed", ThirdPartyWorkerProtocol.ActionKind.DOCUMENT_GET, json.parse("{\"key\":1}")));
        assertInvalidArgument(malformed);
    }

    @Test
    void worker协议值对象拒绝歧义身份和不一致结果() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ThirdPartyWorkerProtocol.Action(
                        "bad id", ThirdPartyWorkerProtocol.ActionKind.DOCUMENT_GET, json.parse("{}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ThirdPartyWorkerProtocol.ActionResult(
                        "id",
                        true,
                        json.parse("{}"),
                        java.util.Optional.of(new ThirdPartyWorkerProtocol.Failure("X", "x"))));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ThirdPartyWorkerProtocol.ActionResult(
                        "id", false, json.parse("{}"), java.util.Optional.empty()));
    }

    private ThirdPartyWorkerProtocol.ActionResult execute(ThirdPartyWorkerProtocol.Action action) {
        return actions.execute(bundle, permission(), new CancellationSource(), List.of(action))
                .getFirst();
    }

    private PermissionProfile permission() {
        return ThirdPartyPermissionPolicy.descriptorPermission(bundle.manifest(), bundle.root(), json);
    }

    private ThirdPartyWorkerProtocol.Action put(
            String id, String key, long expectedRevision, CanonicalPayload payload) {
        return action(
                id,
                ThirdPartyWorkerProtocol.ActionKind.DOCUMENT_PUT,
                Map.of("key", key, "expectedRevision", expectedRevision, "payload", payload));
    }

    private ThirdPartyWorkerProtocol.Action action(
            String id, ThirdPartyWorkerProtocol.ActionKind kind, Object arguments) {
        return new ThirdPartyWorkerProtocol.Action(id, kind, json.encode(arguments));
    }

    private static void assertInvalidArgument(ThirdPartyWorkerProtocol.ActionResult result) {
        assertFalse(result.ok());
        assertEquals("INVALID_ARGUMENT", result.error().orElseThrow().code());
    }

    private record RevisionResult(long revision) {}

    private record DocumentResult(
            boolean present,
            Optional<String> key,
            long revision,
            Optional<CanonicalPayload> payload,
            Optional<Instant> updatedAt) {}

    private record BlobResult(String digest) {}

    private static final class StubNetwork implements NetworkBroker {
        private BrokerResponse response;
        private Exception failure;
        private BrokerRequest request;
        private PermissionProfile permission;
        private CancellationToken cancellation;

        @Override
        public BrokerResponse exchange(
                BrokerRequest request, PermissionProfile permission, CancellationToken cancellation) throws Exception {
            this.request = request;
            this.permission = permission;
            this.cancellation = cancellation;
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
