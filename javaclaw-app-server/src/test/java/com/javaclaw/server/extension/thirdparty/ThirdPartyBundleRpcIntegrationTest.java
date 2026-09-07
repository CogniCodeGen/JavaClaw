package com.javaclaw.server.extension.thirdparty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.AttachmentMetadata;
import com.javaclaw.api.AttachmentScope;
import com.javaclaw.api.CancellationToken;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.BundleRpcContracts;
import com.javaclaw.protocol.CapabilityAdvertisement;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.ProtocolErrorCode;
import com.javaclaw.protocol.ProtocolVersion;
import com.javaclaw.protocol.RpcId;
import com.javaclaw.protocol.WriteCommand;
import com.javaclaw.runtime.ModelCapabilities;
import com.javaclaw.runtime.ModelEventSink;
import com.javaclaw.runtime.ModelFinishReason;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.runtime.ModelInvocation;
import com.javaclaw.runtime.ModelInvocationResult;
import com.javaclaw.runtime.ModelUsage;
import com.javaclaw.server.AppServerBootstrap;
import com.javaclaw.server.rpc.AppServerSession;
import com.javaclaw.server.testkit.AttachmentRpcTestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThirdPartyBundleRpcIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-01T05:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void attachmentTrustBundle和Trash形成可重启恢复闭环() throws Exception {
        Path dataRoot = temporaryDirectory.resolve("data-v6");
        KeyPair keys = ThirdPartyBundleTestFixtures.keyPair();
        var json = new com.javaclaw.protocol.CanonicalJson();
        byte[] archive = Files.readAllBytes(ThirdPartyBundleTestFixtures.archive(temporaryDirectory, json, keys));

        BundleRpcContracts.TrashEntry removed;
        try (var components = AppServerBootstrap.create(dataRoot, fixedClock(), new UnusedModel())) {
            AppServerSession session = components.newSession();
            initialize(session, components);
            importTrustKey(session, components, keys);
            AttachmentMetadata attachment = createAttachment(
                    session, components, "bundle-attachment", BundleRpcContracts.BUNDLE_MEDIA_TYPE, archive);
            BundleRpcContracts.StageResult staged = stage(session, components, attachment);
            BundleRpcContracts.Bundle installed = install(session, components, staged);

            assertEquals(installed, readBundle(session, components, installed.id()));
            assertEquals(List.of(installed), listBundles(session, components));
            removed = uninstall(session, components, installed, "uninstall");
            BundleRpcContracts.Bundle restored = restore(session, components, removed);

            assertEquals(2, restored.revision());
            assertEquals("DISABLED", restored.state());
            assertEquals(
                    BundleRpcContracts.TrashState.RESTORED,
                    readTrash(session, components, removed.trashId()).state());
        }

        try (var components = AppServerBootstrap.create(dataRoot, fixedClock(), new UnusedModel())) {
            AppServerSession session = components.newSession();
            initialize(session, components);
            BundleRpcContracts.Bundle restored = readBundle(session, components, removed.extensionId());
            BundleRpcContracts.TrustKey revoked = revokeTrustKey(session, components, restored.signingKeyId());
            BundleRpcContracts.Bundle disabled = readBundle(session, components, restored.id());
            BundleRpcContracts.TrashEntry trashed = uninstall(session, components, disabled, "uninstall-restored");
            BundleRpcContracts.TrashEntry purged = purge(session, components, trashed);

            assertEquals(BundleRpcContracts.TrustState.REVOKED, revoked.state());
            assertEquals("DISABLED", disabled.state());
            assertEquals(BundleRpcContracts.TrashState.PURGED, purged.state());
            assertFalse(listTrash(session, components).isEmpty());
        }
    }

    @Test
    void staging拒绝非Attachment路径入口和错误摘要确认() throws Exception {
        Path dataRoot = temporaryDirectory.resolve("data-v6");
        KeyPair keys = ThirdPartyBundleTestFixtures.keyPair();
        var json = new com.javaclaw.protocol.CanonicalJson();
        byte[] archive = Files.readAllBytes(ThirdPartyBundleTestFixtures.archive(temporaryDirectory, json, keys));
        try (var components = AppServerBootstrap.create(dataRoot, fixedClock(), new UnusedModel())) {
            AppServerSession session = components.newSession();
            initialize(session, components);
            importTrustKey(session, components, keys);
            AttachmentMetadata attachment =
                    createAttachment(session, components, "bundle", BundleRpcContracts.BUNDLE_MEDIA_TYPE, archive);
            BundleRpcContracts.StageResult staged = stage(session, components, attachment);
            JsonRpcResponse response = session.handle(request(
                    components,
                    "bad-install",
                    "extension/bundle/install",
                    command(
                            components,
                            "bad-install",
                            0,
                            new BundleRpcContracts.CommitPayload(staged.stagingId(), "b".repeat(64)))));

            assertEquals(
                    ProtocolErrorCode.PERMISSION_DENIED,
                    response.error().orElseThrow().code());
        }
    }

    private static void importTrustKey(
            AppServerSession session, AppServerBootstrap.Components components, KeyPair keys) {
        AttachmentMetadata attachment = createAttachment(
                session,
                components,
                "trust-key",
                BundleRpcContracts.PUBLIC_KEY_MEDIA_TYPE,
                keys.getPublic().getEncoded());
        var pointer = new BundleRpcContracts.AttachmentPointer(attachment.digest(), attachment.digest());
        BundleRpcContracts.TrustKey key = decode(
                        session,
                        components,
                        "trust-import",
                        "extension/trustKey/import",
                        command(
                                components,
                                "trust-import",
                                0,
                                new BundleRpcContracts.TrustKeyImportPayload(
                                        ThirdPartyBundleTestFixtures.KEY_ID, pointer)),
                        BundleRpcContracts.TrustKeyResult.class)
                .key();
        assertEquals(BundleRpcContracts.TrustState.ACTIVE, key.state());
    }

    private static AttachmentMetadata createAttachment(
            AppServerSession session,
            AppServerBootstrap.Components components,
            String key,
            String mediaType,
            byte[] content) {
        return AttachmentRpcTestClient.upload(
                session, components.json(), key, AttachmentScope.global(), mediaType, content);
    }

    private static BundleRpcContracts.StageResult stage(
            AppServerSession session, AppServerBootstrap.Components components, AttachmentMetadata attachment) {
        var pointer = new BundleRpcContracts.AttachmentPointer(attachment.digest(), attachment.digest());
        return decode(
                session,
                components,
                "bundle-stage",
                "extension/bundle/stage",
                command(components, "bundle-stage", 0, new BundleRpcContracts.StagePayload(pointer)),
                BundleRpcContracts.StageResult.class);
    }

    private static BundleRpcContracts.Bundle install(
            AppServerSession session, AppServerBootstrap.Components components, BundleRpcContracts.StageResult staged) {
        return decode(
                        session,
                        components,
                        "bundle-install",
                        "extension/bundle/install",
                        command(
                                components,
                                "bundle-install",
                                0,
                                new BundleRpcContracts.CommitPayload(staged.stagingId(), staged.manifestDigest())),
                        BundleRpcContracts.BundleResult.class)
                .bundle();
    }

    private static BundleRpcContracts.TrashEntry uninstall(
            AppServerSession session,
            AppServerBootstrap.Components components,
            BundleRpcContracts.Bundle bundle,
            String key) {
        return decode(
                        session,
                        components,
                        key,
                        "extension/bundle/uninstall",
                        command(components, key, bundle.revision(), new BundleRpcContracts.BundlePayload(bundle.id())),
                        BundleRpcContracts.TrashResult.class)
                .entry();
    }

    private static BundleRpcContracts.Bundle restore(
            AppServerSession session, AppServerBootstrap.Components components, BundleRpcContracts.TrashEntry trash) {
        return decode(
                        session,
                        components,
                        "trash-restore",
                        "extension/bundle/trash/restore",
                        command(
                                components,
                                "trash-restore",
                                trash.revision(),
                                new BundleRpcContracts.TrashPayload(trash.trashId())),
                        BundleRpcContracts.BundleResult.class)
                .bundle();
    }

    private static BundleRpcContracts.TrustKey revokeTrustKey(
            AppServerSession session, AppServerBootstrap.Components components, String keyId) {
        BundleRpcContracts.TrustKey current = decode(
                        session,
                        components,
                        "trust-read",
                        "extension/trustKey/read",
                        new BundleRpcContracts.TrustKeyPayload(keyId),
                        BundleRpcContracts.TrustKeyResult.class)
                .key();
        return decode(
                        session,
                        components,
                        "trust-revoke",
                        "extension/trustKey/revoke",
                        command(
                                components,
                                "trust-revoke",
                                current.revision(),
                                new BundleRpcContracts.TrustKeyPayload(keyId)),
                        BundleRpcContracts.TrustKeyResult.class)
                .key();
    }

    private static BundleRpcContracts.TrashEntry purge(
            AppServerSession session, AppServerBootstrap.Components components, BundleRpcContracts.TrashEntry trash) {
        return decode(
                        session,
                        components,
                        "trash-purge",
                        "extension/bundle/trash/purge",
                        command(
                                components,
                                "trash-purge",
                                trash.revision(),
                                new BundleRpcContracts.TrashPurgePayload(trash.trashId(), "PURGE " + trash.trashId())),
                        BundleRpcContracts.TrashResult.class)
                .entry();
    }

    private static BundleRpcContracts.Bundle readBundle(
            AppServerSession session, AppServerBootstrap.Components components, String extensionId) {
        return decode(
                        session,
                        components,
                        "bundle-read",
                        "extension/bundle/read",
                        new BundleRpcContracts.BundlePayload(extensionId),
                        BundleRpcContracts.BundleResult.class)
                .bundle();
    }

    private static List<BundleRpcContracts.Bundle> listBundles(
            AppServerSession session, AppServerBootstrap.Components components) {
        return decode(
                        session,
                        components,
                        "bundle-list",
                        "extension/bundle/list",
                        new CanonicalPayload("{}"),
                        BundleRpcContracts.BundleListResult.class)
                .bundles();
    }

    private static BundleRpcContracts.TrashEntry readTrash(
            AppServerSession session, AppServerBootstrap.Components components, String trashId) {
        return decode(
                        session,
                        components,
                        "trash-read",
                        "extension/bundle/trash/read",
                        new BundleRpcContracts.TrashPayload(trashId),
                        BundleRpcContracts.TrashResult.class)
                .entry();
    }

    private static List<BundleRpcContracts.TrashEntry> listTrash(
            AppServerSession session, AppServerBootstrap.Components components) {
        return decode(
                        session,
                        components,
                        "trash-list",
                        "extension/bundle/trash/list",
                        new CanonicalPayload("{}"),
                        BundleRpcContracts.TrashListResult.class)
                .entries();
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static void initialize(AppServerSession session, AppServerBootstrap.Components components) {
        InitializeParams params = new InitializeParams(
                ProtocolVersion.CURRENT,
                new ClientInfo("bundle-test", "5.0"),
                new CapabilityAdvertisement(Set.of("core.item-envelope"), Set.of()));
        assertTrue(session.handle(request(components, "init", "initialize/session", params))
                .result()
                .isPresent());
    }

    private static WriteCommand command(
            AppServerBootstrap.Components components, String key, long revision, Object payload) {
        return new WriteCommand(key, revision, components.json().encode(payload));
    }

    private static JsonRpcRequest request(
            AppServerBootstrap.Components components, String id, String method, Object params) {
        return new JsonRpcRequest(new RpcId(id), method, components.json().encode(params));
    }

    private static <T> T decode(
            AppServerSession session,
            AppServerBootstrap.Components components,
            String id,
            String method,
            Object params,
            Class<T> type) {
        JsonRpcResponse response = session.handle(request(components, id, method, params));
        return components.json().decode(response.result().orElseThrow(), type);
    }

    private static final class UnusedModel implements ModelGateway {
        @Override
        public ModelCapabilities capabilities(String modelId) {
            return new ModelCapabilities(false, false, false, false, false, false, false);
        }

        @Override
        public ModelInvocationResult invoke(
                com.javaclaw.api.TurnId turnId,
                ModelInvocation invocation,
                ModelEventSink events,
                CancellationToken cancellation) {
            return new ModelInvocationResult(
                    "unused",
                    List.of(),
                    ModelUsage.zero(),
                    Optional.empty(),
                    Optional.empty(),
                    ModelFinishReason.COMPLETE);
        }
    }
}
