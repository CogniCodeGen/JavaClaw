package com.javaclaw.server;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.javaclaw.api.ApprovalRequirement;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.FilePermission;
import com.javaclaw.api.NetworkPermission;
import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.ProcessPermission;
import com.javaclaw.api.ResourceLimits;
import com.javaclaw.api.ToolPermission;
import com.javaclaw.api.ToolRisk;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.SiteAccountContracts;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionAvailability;
import com.javaclaw.extension.spi.ExtensionDescriptor;
import com.javaclaw.extension.spi.ExtensionId;
import com.javaclaw.extension.spi.ExtensionRequirements;
import com.javaclaw.extension.spi.ExtensionState;
import com.javaclaw.extension.spi.ExtensionTransaction;
import com.javaclaw.extension.spi.ExtensionTrust;
import com.javaclaw.extension.spi.VersionedDocument;
import com.javaclaw.protocol.BuiltinExtensionRpcContracts;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.CapabilityAdvertisement;
import com.javaclaw.protocol.ClientInfo;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.InitializeParams;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.RpcId;
import com.javaclaw.runtime.ModelGateway;
import com.javaclaw.server.persistence.CommandIdentity;
import com.javaclaw.server.persistence.CoreCommandService;
import com.javaclaw.server.persistence.ExtensionCatalogRepository;
import com.javaclaw.server.persistence.H2Database;
import com.javaclaw.server.persistence.H2ManagedExtensionStore;
import com.javaclaw.server.rpc.AppServerSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteBundleUpgradeBootstrapTest {
    private static final Instant SAVED_AT = Instant.parse("2026-09-01T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(SAVED_AT.plusSeconds(60), ZoneOffset.UTC);
    private static final ExtensionId SITE = new ExtensionId(BuiltinExtensionIds.SITE);
    private static final String SITE_ID = "retained-site";
    private static final String ACCOUNT_ID = "retained-account";
    private static final URI ORIGIN = URI.create("https://retained.example");
    private final CanonicalJson json = new CanonicalJson();

    @TempDir
    Path directory;

    @Test
    void 已启用旧Site真实库启动升级到版本二且再次启动保留网站与账号文档() throws Exception {
        SavedDatabase saved = prepareOldDatabase(false);
        var first = restartAndRead(saved, ExtensionState.ENABLED, 1);
        var second = restartAndRead(saved, ExtensionState.ENABLED, 1);
        assertEquals(first, second, "重复启动不能再次修改 descriptor 或启停版本");
        assertStoredDocuments(saved);
    }

    @Test
    void 已禁用旧Site真实库仍能启动且升级不重新启用或覆盖账号迁移标记() throws Exception {
        SavedDatabase saved = prepareOldDatabase(true);
        var first = restartAndRead(saved, ExtensionState.DISABLED, 2);
        var second = restartAndRead(saved, ExtensionState.DISABLED, 2);
        assertEquals(first, second);
        assertStoredDocuments(saved);
    }

    private SavedDatabase prepareOldDatabase(boolean disabled) throws Exception {
        Path root = directory.resolve("data-v6");
        H2Database database = new H2Database(root);
        database.initialize();
        var core = new CoreCommandService(database, json, Clock.fixed(SAVED_AT, ZoneOffset.UTC));
        Path workspaceRoot = Files.createDirectories(directory.resolve("workspace"));
        WorkspaceId workspace = core.createWorkspace(identity("workspace/create", 0), "保留工作区", workspaceRoot)
                .id();
        var catalog = new ExtensionCatalogRepository(database, json, Clock.fixed(SAVED_AT, ZoneOffset.UTC));
        catalog.installBuiltIn(legacyDescriptor());
        if (disabled) {
            catalog.disable(identity("extension/builtin/disable", 1), SITE.value());
        }
        assertEquals(1, catalog.requireBuiltIn(SITE.value()).descriptorRevision());
        String accountCollection = "accounts." + workspace + '.'
                + json.encode(Map.of("workspaceId", workspace, "siteId", SITE_ID))
                        .sha256()
                        .substring(0, 32);
        var store = new H2ManagedExtensionStore(database, Clock.fixed(SAVED_AT, ZoneOffset.UTC));
        List<SavedDocument> documents = store.inTransaction(
                SITE,
                transaction -> List.of(
                        storeDocument(transaction, "documents." + workspace, SITE_ID, json.encode(site())),
                        storeDocument(
                                transaction,
                                "account-index." + workspace,
                                SITE_ID,
                                json.encode(Map.of("revision", 1, "defaultAccount", ACCOUNT_ID))),
                        storeDocument(transaction, accountCollection, ACCOUNT_ID, account())));
        // H2 不持有全局连接；上述各事务已关闭旧连接，后续组合根重新打开同一磁盘数据库。
        return new SavedDatabase(root, workspace, documents);
    }

    private BuiltinExtensionRpcContracts.Status restartAndRead(
            SavedDatabase saved, ExtensionState expected, long stateRevision) throws Exception {
        try (var components = AppServerBootstrap.create(saved.root(), CLOCK, forbiddenModels(), ignored -> {});
                var session = components.newSession()) {
            rpc(
                    session,
                    "initialize/session",
                    json.encode(new InitializeParams(
                            3,
                            new ClientInfo("site-upgrade-fixture", "6"),
                            new CapabilityAdvertisement(Set.of(), Set.of()))));
            var result = rpc(
                    session,
                    "extension/builtin/read",
                    json.encode(new BuiltinExtensionRpcContracts.ExtensionPayload(SITE.value())));
            var status = json.decode(result, BuiltinExtensionRpcContracts.StatusResult.class)
                    .extension();
            assertEquals(2, status.descriptorRevision());
            assertEquals(expected, status.state());
            assertEquals(stateRevision, status.stateRevision());
            assertStoredDocuments(saved);
            if (expected == ExtensionState.ENABLED) {
                assertPublicData(session, saved.workspace());
            } else {
                var denied = request(
                        session,
                        "extension/query",
                        call(saved.workspace(), "read", new DocumentContracts.Key(SITE_ID)));
                assertTrue(denied.error().isPresent(), "升级必须继续遵守用户原有禁用决定");
            }
            return status;
        }
    }

    private void assertPublicData(AppServerSession session, WorkspaceId workspace) {
        var siteResult = json.decode(
                rpc(session, "extension/query", call(workspace, "read", new DocumentContracts.Key(SITE_ID))),
                ExtensionRpcContracts.CallResult.class);
        assertEquals(
                SiteContracts.Projection.from(site()),
                json.decode(siteResult.payload(), SiteContracts.Projection.class));
        var accountResult = json.decode(
                rpc(
                        session,
                        "extension/query",
                        call(workspace, "account/list", new SiteAccountContracts.ListRequest(SITE_ID))),
                ExtensionRpcContracts.CallResult.class);
        var accounts = json.decode(accountResult.payload(), SiteAccountContracts.AccountList.class)
                .accounts();
        assertEquals(1, accounts.size());
        assertEquals(ACCOUNT_ID, accounts.getFirst().accountId());
        assertEquals("保留账号", accounts.getFirst().name());
        assertEquals(7, accounts.getFirst().securityRevision());
        assertEquals(4, accounts.getFirst().stateRevision());
        assertTrue(accounts.getFirst().defaultAccount());
    }

    private void assertStoredDocuments(SavedDatabase saved) throws Exception {
        var store = new H2ManagedExtensionStore(new H2Database(saved.root()), CLOCK);
        store.inTransaction(SITE, transaction -> {
            for (SavedDocument document : saved.documents()) {
                assertEquals(
                        document.document(),
                        transaction
                                .get(document.collection(), document.document().key())
                                .orElseThrow());
            }
            return null;
        });
    }

    private CanonicalPayload account() {
        return json.parse("""
                {"accountId":"retained-account","siteId":"retained-site","revision":1,
                 "securityRevision":7,"stateRevision":4,"siteAuthorityRevision":1,"name":"保留账号",
                 "enabled":true,"loginSecret":null,"browserState":null,"updatedAt":"2026-09-01T01:00:00Z"}
                """);
    }

    private static SavedDocument storeDocument(
            ExtensionTransaction transaction, String collection, String key, CanonicalPayload payload) {
        assertEquals(1, transaction.put(collection, key, 0, payload));
        return new SavedDocument(collection, transaction.get(collection, key).orElseThrow());
    }

    private static SiteContracts.Site site() {
        return new SiteContracts.Site(
                SITE_ID,
                1,
                1,
                "保留网站",
                ORIGIN,
                Set.of(ORIGIN),
                SiteContracts.SiteCredential.none(),
                Optional.empty(),
                true,
                SAVED_AT);
    }

    /** 固定 HEAD 的旧清单，不调用新版 Site 构造器，避免升级测试随待测实现一起漂移。 */
    private static ExtensionDescriptor legacyDescriptor() {
        PermissionProfile permission = new PermissionProfile(
                SITE.value() + ".ceiling",
                1,
                new FilePermission(List.of(), List.of(), false, false),
                new NetworkPermission(Set.of(NetworkPermission.ANY_HOST), Set.of(NetworkPermission.ANY_PORT), true),
                new ProcessPermission(Set.of(), false, Duration.ofSeconds(30)),
                new ToolPermission(Set.of("site_search", "site_snapshot"), ToolRisk.NETWORK, ApprovalRequirement.NONE),
                new ResourceLimits(256L * 1024 * 1024, 4L * 1024 * 1024, 1, 32));
        return new ExtensionDescriptor(
                SITE,
                "站点",
                "5.0.0",
                1,
                Set.of(
                        ContributionKind.QUERY,
                        ContributionKind.COMMAND,
                        ContributionKind.VIEW,
                        ContributionKind.TOOL,
                        ContributionKind.SERVICE),
                new ExtensionRequirements(ExtensionTrust.BUILT_IN, ExtensionAvailability.OPTIONAL, 2, permission));
    }

    private static ModelGateway forbiddenModels() {
        return (ModelGateway) Proxy.newProxyInstance(
                ModelGateway.class.getClassLoader(), new Class<?>[] {ModelGateway.class}, (proxy, method, args) -> {
                    throw new AssertionError("旧库启动迁移不得调用模型");
                });
    }

    private CanonicalPayload call(WorkspaceId workspace, String operation, Object payload) {
        return json.encode(new ExtensionRpcContracts.CallPayload(
                SITE.value(), workspace, Optional.empty(), Optional.empty(), operation, json.encode(payload)));
    }

    private CanonicalPayload rpc(AppServerSession session, String method, CanonicalPayload payload) {
        var response = request(session, method, payload);
        assertTrue(response.error().isEmpty(), () -> response.error().toString());
        return response.result().orElseThrow();
    }

    private static JsonRpcResponse request(AppServerSession session, String method, CanonicalPayload payload) {
        return session.handle(new JsonRpcRequest(new RpcId(UUID.randomUUID().toString()), method, payload));
    }

    private static CommandIdentity identity(String method, long revision) {
        return new CommandIdentity(method, UUID.randomUUID().toString(), revision, "a".repeat(64));
    }

    private record SavedDocument(String collection, VersionedDocument document) {}

    private record SavedDatabase(Path root, WorkspaceId workspace, List<SavedDocument> documents) {}
}
