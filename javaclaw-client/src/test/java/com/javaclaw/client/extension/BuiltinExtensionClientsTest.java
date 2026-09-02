package com.javaclaw.client.extension;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;
import com.javaclaw.api.ItemId;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.BuiltinExtensionIds;
import com.javaclaw.builtin.contracts.DocumentContracts;
import com.javaclaw.builtin.contracts.KnowledgeContracts;
import com.javaclaw.builtin.contracts.MemoryContracts;
import com.javaclaw.builtin.contracts.SiteContracts;
import com.javaclaw.builtin.contracts.SiteManagementContracts;
import com.javaclaw.builtin.contracts.SkillContracts;
import com.javaclaw.builtin.contracts.SkillTransferContracts;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.RpcClientConnection;
import com.javaclaw.client.testkit.ScriptedRpcConnection;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;
import com.javaclaw.protocol.JsonRpcRequest;
import com.javaclaw.protocol.JsonRpcResponse;
import com.javaclaw.protocol.WriteCommand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinExtensionClientsTest {
    private static final CanonicalJson JSON = new CanonicalJson();
    private static final WorkspaceId WORKSPACE = WorkspaceId.parse("114fdfd7-d4e7-42f4-9563-dd552b61f97d");
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final CommandOptions CREATE = new CommandOptions("create", 0);
    private static final CommandOptions UPDATE = new CommandOptions("update", 1);
    private static final MemoryContracts.Memory MEMORY = new MemoryContracts.Memory(
            "memory",
            1,
            MemoryContracts.MemoryKind.FACT,
            "workspace",
            "architecture",
            Set.of("v5"),
            false,
            Optional.empty(),
            NOW,
            NOW);
    private static final MemoryContracts.Source MEMORY_SOURCE = new MemoryContracts.Source(
            WORKSPACE,
            ThreadId.parse("dc914106-e26f-4ab1-a3c5-001c12cb9ec1"),
            ItemId.parse("ba7261b1-94d6-4f31-96ba-c1d8e8d5a25e"),
            "architecture");
    private static final MemoryContracts.Proposal MEMORY_PROPOSAL = new MemoryContracts.Proposal(
            "proposal",
            1,
            new MemoryContracts.LearningProposalRequest(
                    "proposal",
                    MemoryContracts.MemoryKind.FACT,
                    "workspace",
                    "architecture",
                    Set.of("v5"),
                    MEMORY_SOURCE),
            Set.of(),
            MemoryContracts.ProposalState.PENDING,
            Optional.empty(),
            NOW,
            NOW);
    private static final SkillContracts.Draft DRAFT =
            new SkillContracts.Draft("review", 1, "代码审查", "审查 Java", "执行审查", List.of(), NOW, NOW);
    private static final SkillContracts.PublishedSkill PUBLISHED = new SkillContracts.PublishedSkill(
            "review", 2, 1, "a".repeat(64), "代码审查", "审查 Java", "执行审查", List.of(), true, NOW, NOW);
    private static final SkillContracts.Proposal SKILL_PROPOSAL = new SkillContracts.Proposal(
            "skill-proposal",
            1,
            new SkillContracts.ProposeRequest("skill-proposal", "review", "代码审查", "审查 Java", "执行审查"),
            SkillContracts.ProposalState.PENDING,
            Optional.empty(),
            NOW,
            NOW);
    private static final AttachmentRef SKILL_TRANSFER_ATTACHMENT =
            new AttachmentRef("e".repeat(64), SkillTransferContracts.MARKDOWN_MEDIA_TYPE, "review.skill.md", 128);
    private static final AttachmentRef ATTACHMENT = new AttachmentRef("a".repeat(64), "text/plain", "guide.txt", 4);
    private static final KnowledgeContracts.Source SOURCE =
            new KnowledgeContracts.Source("source", 1, "Guide", ATTACHMENT, "generation-1", NOW);
    private static final KnowledgeContracts.Generation GENERATION = new KnowledgeContracts.Generation(
            "generation-1",
            1,
            "source",
            1,
            ATTACHMENT.digest(),
            "plain-v1",
            KnowledgeContracts.RetrievalMode.KEYWORD,
            Optional.empty(),
            0,
            1,
            4,
            Optional.empty(),
            NOW);
    private static final String LOGIN_SESSION_ID = "b74c170e-d85d-4de8-b341-8a74b89c0854";
    private static final URI SITE_ORIGIN = URI.create("https://docs.example.com");
    private static final SiteContracts.Site SITE = new SiteContracts.Site(
            "docs",
            1,
            1,
            "Docs",
            SITE_ORIGIN,
            Set.of(SITE_ORIGIN),
            SiteContracts.SiteCredential.none(),
            Optional.empty(),
            true,
            NOW);
    private static final CredentialMetadata BROWSER_CREDENTIAL = new CredentialMetadata(
            new CredentialRef(SiteContracts.BROWSER_CREDENTIAL_NAMESPACE, "browser-state"), 1, NOW);
    private static final SiteContracts.Site SAVED_SITE = new SiteContracts.Site(
            "docs",
            2,
            2,
            "Docs",
            SITE_ORIGIN,
            Set.of(SITE_ORIGIN),
            new SiteContracts.SiteCredential(
                    SiteContracts.CredentialKind.BROWSER_STORAGE,
                    Optional.of(BROWSER_CREDENTIAL.reference()),
                    Optional.empty()),
            Optional.empty(),
            true,
            NOW);

    @Test
    void memoryFacadeUsesDedicatedLifecycleAndLearningContracts() {
        MemoryClient memories = new BuiltinExtensionClients(extensionClient()).memories();
        MemoryContracts.CreateRequest create = new MemoryContracts.CreateRequest(
                "memory",
                MemoryContracts.MemoryKind.FACT,
                "workspace",
                "architecture",
                Set.of("v5"),
                false,
                Optional.empty());

        assertEquals(MEMORY, memories.read(WORKSPACE, "memory"));
        assertEquals(List.of(MEMORY), memories.list(WORKSPACE, "", 10).documents());
        assertEquals(MEMORY, memories.create(WORKSPACE, create, CREATE));
        assertEquals(new DocumentContracts.Deleted("memory"), memories.tombstone(WORKSPACE, "memory", UPDATE));
        assertEquals(
                new MemoryContracts.SearchResult(List.of(MEMORY)),
                memories.search(
                        WORKSPACE, new MemoryContracts.SearchRequest("architecture", Set.of(), Set.of("v5"), 10)));
        assertEquals(
                MemoryContracts.LearningPolicy.SUGGEST,
                memories.learningSettings(WORKSPACE).policy());
        assertEquals(
                MemoryContracts.LearningAction.PROPOSED,
                memories.propose(WORKSPACE, MEMORY_PROPOSAL.candidate(), CREATE).action());
        assertEquals(MEMORY_PROPOSAL, memories.readProposal(WORKSPACE, "proposal"));
        assertEquals(
                1,
                memories.history(WORKSPACE, new MemoryContracts.HistoryRequest("memory", 0, 10))
                        .entries()
                        .size());
        assertEquals(1, memories.stats(WORKSPACE).active());
    }

    @Test
    void skillFacadeSeparatesProposalDraftPublishedAndFrozenRead() {
        SkillClient skills = new BuiltinExtensionClients(extensionClient()).skills();
        TurnId turnId = TurnId.parse("097e5d9f-ddb7-4828-9574-33527ec4e86b");
        SkillContracts.Summary summary =
                new SkillContracts.Summary("review", 2, PUBLISHED.digest(), PUBLISHED.name(), PUBLISHED.description());

        assertEquals(DRAFT, skills.readDraft(WORKSPACE, "review"));
        assertEquals(List.of(DRAFT), skills.listDrafts(WORKSPACE, "", 10).documents());
        assertEquals(
                DRAFT,
                skills.saveContent(
                        WORKSPACE,
                        new SkillContracts.SaveContentRequest(
                                DRAFT.id(), DRAFT.name(), DRAFT.description(), DRAFT.instructions()),
                        CREATE));
        assertEquals(PUBLISHED, skills.publish(WORKSPACE, new SkillContracts.PublishRequest("review", 1), UPDATE));
        assertEquals(PUBLISHED, skills.readPublished(WORKSPACE, "review"));
        assertEquals(List.of(PUBLISHED), skills.listPublished(WORKSPACE, "", 10).documents());
        assertEquals(
                List.of(summary),
                skills.search(WORKSPACE, turnId, new SkillContracts.SearchRequest("审查", 10))
                        .matches());
        assertEquals(
                PUBLISHED,
                skills.readFrozen(
                        WORKSPACE, turnId, new SkillContracts.PublishedReadRequest("review", 2, PUBLISHED.digest())));
        assertEquals(SKILL_PROPOSAL, skills.propose(WORKSPACE, SKILL_PROPOSAL.candidate(), CREATE));
        assertEquals(
                new SkillTransferContracts.ImportResult(DRAFT, SkillTransferContracts.TransferFormat.MARKDOWN),
                skills.importDraft(
                        WORKSPACE, new SkillTransferContracts.ImportRequest(SKILL_TRANSFER_ATTACHMENT), CREATE));
        assertEquals(
                new SkillTransferContracts.ExportResult(
                        SKILL_TRANSFER_ATTACHMENT,
                        PUBLISHED.id(),
                        PUBLISHED.revision(),
                        PUBLISHED.digest(),
                        SkillTransferContracts.TransferFormat.MARKDOWN),
                skills.exportPublished(
                        WORKSPACE,
                        new SkillTransferContracts.ExportRequest(
                                PUBLISHED.id(), SkillTransferContracts.TransferFormat.MARKDOWN),
                        new CommandOptions("export", PUBLISHED.revision())));
        assertFalse(skills.resourceExecutionAvailability(WORKSPACE).executable());
    }

    @Test
    void knowledgeFacadeUsesAttachmentJobsAndImmutableGenerations() {
        KnowledgeClient knowledge = new BuiltinExtensionClients(extensionClient()).knowledge();
        KnowledgeContracts.SearchRequest search =
                new KnowledgeContracts.SearchRequest("guide", Set.of("text/plain"), 10);

        assertEquals(SOURCE, knowledge.readSource(WORKSPACE, "source"));
        assertEquals(
                List.of(SOURCE),
                knowledge
                        .listSources(WORKSPACE, new KnowledgeContracts.PageRequest("", 10))
                        .values());
        assertEquals(GENERATION, knowledge.readGeneration(WORKSPACE, "generation-1"));
        assertEquals(
                List.of(GENERATION),
                knowledge
                        .listGenerations(WORKSPACE, new KnowledgeContracts.PageRequest("", 10))
                        .values());
        assertEquals(new DocumentContracts.Deleted("source"), knowledge.deleteSource(WORKSPACE, "source", UPDATE));
        assertEquals(
                new KnowledgeContracts.SearchResult(
                        List.of(new KnowledgeContracts.SearchMatch(
                                SOURCE, GENERATION, List.of("excerpt"), 0.8, KnowledgeContracts.RetrievalMode.KEYWORD)),
                        false),
                knowledge.search(WORKSPACE, search));
        assertEquals(
                new KnowledgeContracts.ImportAccepted("job-1", "source", 2),
                knowledge.importSource(WORKSPACE, importRequest(), UPDATE));
    }

    @Test
    void siteFacadeKeepsLoginStorageOpaqueAcrossTypedSdk() {
        SiteClient sites = new BuiltinExtensionClients(extensionClient()).sites();
        SiteContracts.LoginControlRequest control = new SiteContracts.LoginControlRequest(LOGIN_SESSION_ID);
        SiteContracts.Projection site = SiteContracts.Projection.from(SITE);

        assertEquals(site, sites.read(WORKSPACE, "docs"));
        assertEquals(List.of(site), sites.list(WORKSPACE, "", 10).documents());
        assertEquals(site, sites.create(WORKSPACE, siteRequest(), CREATE));
        assertEquals(SiteContracts.Projection.from(SAVED_SITE), sites.update(WORKSPACE, siteRequest(), UPDATE));
        assertEquals(
                SiteContracts.Projection.from(SAVED_SITE),
                sites.bindCredential(
                        WORKSPACE,
                        new SiteManagementContracts.CredentialBindRequest(
                                "docs", 1, SiteContracts.CredentialKind.BEARER, "site-secret", Optional.empty()),
                        UPDATE));
        assertEquals(
                SiteContracts.Projection.from(SAVED_SITE),
                sites.clearCredential(WORKSPACE, new SiteManagementContracts.AuthorityClearRequest("docs", 1), UPDATE));
        assertEquals(
                SiteContracts.Projection.from(SAVED_SITE),
                sites.bindPrivateNetwork(
                        WORKSPACE,
                        new SiteManagementContracts.PrivateNetworkBindRequest("docs", 1, "site-network"),
                        UPDATE));
        assertEquals(
                SiteContracts.Projection.from(SAVED_SITE),
                sites.clearPrivateNetwork(
                        WORKSPACE, new SiteManagementContracts.AuthorityClearRequest("docs", 1), UPDATE));
        assertEquals(
                List.of(site),
                sites.search(WORKSPACE, new SiteContracts.SearchRequest("docs", 10))
                        .matches());
        assertEquals(
                SiteContracts.LoginSessionState.READY,
                sites.beginLogin(WORKSPACE, new SiteContracts.LoginBeginRequest("docs", 1, 1), CREATE)
                        .state());
        assertEquals(
                SiteContracts.LoginSessionState.READY,
                sites.loginStatus(WORKSPACE, control).state());
        assertEquals(1, sites.loginSessions(WORKSPACE).sessions().size());
        SiteContracts.LoginSaveResult saved = sites.saveLogin(WORKSPACE, control, CREATE);
        assertEquals(SiteContracts.Projection.from(SAVED_SITE), saved.site());
        assertTrue(saved.credentialConfigured());
        assertEquals(
                SiteContracts.LoginSessionState.CANCELLED,
                sites.cancelLogin(WORKSPACE, control, CREATE).state());
        assertEquals(new DocumentContracts.Deleted("docs"), sites.delete(WORKSPACE, "docs", UPDATE));
    }

    @Test
    void genericExtensionDirectorySchemaAndViewsRemainAvailable() {
        ExtensionClient extensions = extensionClient();
        ViewQueryRequest request = new ViewQueryRequest("overview", Map.of(), "", 20, Optional.empty());
        ExtensionRpcContracts.CallPayload call = new ExtensionRpcContracts.CallPayload(
                "test.extension", WORKSPACE, Optional.empty(), Optional.empty(), "view.read", JSON.encode(request));

        assertEquals("test.extension", extensions.list().getFirst().id());
        assertEquals("schema", extensions.schema("test.extension", "schema").schemaId());
        assertEquals("overview", extensions.views(Optional.empty()).getFirst().viewId());
        assertEquals(
                "overview",
                extensions.views(Optional.of("test.extension")).getFirst().viewId());
        assertEquals("overview", extensions.viewQuery(call).dataSourceId());
        ExtensionRpcContracts.CallPayload mismatch = new ExtensionRpcContracts.CallPayload(
                "test.extension", WORKSPACE, Optional.empty(), Optional.empty(), "view.mismatch", JSON.encode(request));
        assertThrows(IllegalArgumentException.class, () -> extensions.viewQuery(mismatch));
    }

    private ExtensionClient extensionClient() {
        return new ExtensionClient(new RpcClientConnection(
                new ScriptedRpcConnection(BuiltinExtensionClientsTest::respond), JSON, notification -> {}));
    }

    private static KnowledgeContracts.ImportRequest importRequest() {
        return new KnowledgeContracts.ImportRequest(
                "source",
                "Guide",
                ATTACHMENT,
                10_000,
                1_000,
                100,
                KnowledgeContracts.RetrievalPreference.EMBEDDING_PREFERRED);
    }

    private static JsonRpcResponse respond(JsonRpcRequest request) {
        Object result =
                switch (request.method()) {
                    case "extension/list" ->
                        new ExtensionRpcContracts.ListResult(List.of(new ExtensionRpcContracts.Summary(
                                "test.extension", "Test", "5.0.0", 1, "ENABLED", "BUILT_IN", Set.of("QUERY"))));
                    case "extension/schema/read" ->
                        new ExtensionRpcContracts.SchemaResult(
                                "test.extension", "schema", JSON.encode(Map.of("type", "object")));
                    case "extension/view/list" ->
                        new ExtensionRpcContracts.ViewListResult(List.of(new ExtensionRpcContracts.ViewDocument(
                                "test.extension", "overview", JSON.encode(Map.of("schemaVersion", 2)))));
                    case "extension/query" ->
                        callResult(JSON.decode(request.params(), ExtensionRpcContracts.CallPayload.class));
                    case "extension/command" -> {
                        WriteCommand command = JSON.decode(request.params(), WriteCommand.class);
                        yield callResult(JSON.decode(command.payload(), ExtensionRpcContracts.CallPayload.class));
                    }
                    default -> throw new AssertionError("unexpected method " + request.method());
                };
        return JsonRpcResponse.success(request.id(), JSON.encode(result));
    }

    private static ExtensionRpcContracts.CallResult callResult(ExtensionRpcContracts.CallPayload call) {
        CanonicalPayload payload =
                switch (call.extensionId()) {
                    case BuiltinExtensionIds.MEMORY -> memoryPayload(call.operation());
                    case BuiltinExtensionIds.SKILL -> skillPayload(call.operation());
                    case BuiltinExtensionIds.KNOWLEDGE -> knowledgePayload(call);
                    case BuiltinExtensionIds.SITE -> sitePayload(call.operation());
                    case "test.extension" ->
                        JSON.encode(new ViewQueryResult(
                                "overview",
                                List.of(),
                                JSON.encode(Map.of("title", "Overview")),
                                "",
                                false,
                                call.operation().equals("view.mismatch") ? 2 : 1));
                    default -> throw new AssertionError("unexpected extension " + call.extensionId());
                };
        return new ExtensionRpcContracts.CallResult(payload, resultRevision(call));
    }

    private static CanonicalPayload memoryPayload(String operation) {
        return switch (operation) {
            case "read", "create", "update", "pin", "restore" -> JSON.encode(MEMORY);
            case "list" -> page(MEMORY);
            case "tombstone" -> JSON.encode(new DocumentContracts.Deleted("memory"));
            case "search" -> JSON.encode(new MemoryContracts.SearchResult(List.of(MEMORY)));
            case "settings/read", "settings/update" ->
                JSON.encode(new MemoryContracts.LearningSettings(1, MemoryContracts.LearningPolicy.SUGGEST, NOW));
            case "proposal/submit" ->
                JSON.encode(new MemoryContracts.LearningResult(
                        MemoryContracts.LearningAction.PROPOSED, Optional.of(MEMORY_PROPOSAL), Optional.empty()));
            case "proposal/read", "proposal/accept", "proposal/reject" -> JSON.encode(MEMORY_PROPOSAL);
            case "proposal/list" -> page(MEMORY_PROPOSAL);
            case "history" ->
                JSON.encode(new MemoryContracts.HistoryPage(
                        List.of(new MemoryContracts.HistoryEntry(1, MEMORY, false, NOW)), false));
            case "stats" -> JSON.encode(new MemoryContracts.Stats(1, 0, 1, 0));
            default -> throw new AssertionError("unexpected memory operation " + operation);
        };
    }

    private static CanonicalPayload skillPayload(String operation) {
        if (operation.startsWith("draft/")) {
            return skillDraftPayload(operation);
        }
        if (operation.startsWith("proposal/")) {
            return skillProposalPayload(operation);
        }
        if (operation.startsWith("skill/")) {
            return skillTransferPayload(operation);
        }
        return skillPublishedPayload(operation);
    }

    private static CanonicalPayload skillDraftPayload(String operation) {
        return switch (operation) {
            case "draft/read", "draft/save-content", "draft/restore", "draft/resource/add", "draft/resource/remove" ->
                JSON.encode(DRAFT);
            case "draft/list" -> page(DRAFT);
            case "draft/tombstone" -> JSON.encode(new DocumentContracts.Deleted("review"));
            case "draft/history" ->
                JSON.encode(new SkillContracts.DraftHistoryPage(
                        List.of(new SkillContracts.DraftHistoryEntry(1, DRAFT, false, NOW)), false));
            default -> throw new AssertionError("unexpected Skill Draft operation " + operation);
        };
    }

    private static CanonicalPayload skillPublishedPayload(String operation) {
        return switch (operation) {
            case "publish", "enable", "published/read" -> JSON.encode(PUBLISHED);
            case "published/list" -> page(PUBLISHED);
            case "search" ->
                JSON.encode(new SkillContracts.SearchResult(
                        List.of(new SkillContracts.Summary(
                                "review", 2, PUBLISHED.digest(), PUBLISHED.name(), PUBLISHED.description())),
                        "b".repeat(64)));
            case "resource/execution/availability" ->
                JSON.encode(new SkillContracts.ResourceExecutionAvailability(false, "未接入安全执行边界"));
            default -> throw new AssertionError("unexpected published Skill operation " + operation);
        };
    }

    private static CanonicalPayload skillProposalPayload(String operation) {
        return switch (operation) {
            case "proposal/submit", "proposal/read", "proposal/adopt", "proposal/reject" -> JSON.encode(SKILL_PROPOSAL);
            case "proposal/list" -> page(SKILL_PROPOSAL);
            default -> throw new AssertionError("unexpected Skill Proposal operation " + operation);
        };
    }

    private static CanonicalPayload skillTransferPayload(String operation) {
        return switch (operation) {
            case "skill/import" ->
                JSON.encode(
                        new SkillTransferContracts.ImportResult(DRAFT, SkillTransferContracts.TransferFormat.MARKDOWN));
            case "skill/export" ->
                JSON.encode(new SkillTransferContracts.ExportResult(
                        SKILL_TRANSFER_ATTACHMENT,
                        PUBLISHED.id(),
                        PUBLISHED.revision(),
                        PUBLISHED.digest(),
                        SkillTransferContracts.TransferFormat.MARKDOWN));
            default -> throw new AssertionError("unexpected Skill transfer operation " + operation);
        };
    }

    private static CanonicalPayload knowledgePayload(ExtensionRpcContracts.CallPayload call) {
        return switch (call.operation()) {
            case "source/read" -> JSON.encode(SOURCE);
            case "source/list" -> JSON.encode(new KnowledgeContracts.SourcePage(List.of(SOURCE), ""));
            case "generation/read" -> JSON.encode(GENERATION);
            case "generation/list" -> JSON.encode(new KnowledgeContracts.GenerationPage(List.of(GENERATION), ""));
            case "source/delete" -> JSON.encode(new DocumentContracts.Deleted("source"));
            case "search" ->
                JSON.encode(new KnowledgeContracts.SearchResult(
                        List.of(new KnowledgeContracts.SearchMatch(
                                SOURCE, GENERATION, List.of("excerpt"), 0.8, KnowledgeContracts.RetrievalMode.KEYWORD)),
                        false));
            case "source/import" -> JSON.encode(new KnowledgeContracts.ImportAccepted("job-1", "source", 2));
            default -> throw new AssertionError("unexpected knowledge operation " + call.operation());
        };
    }

    private static CanonicalPayload sitePayload(String operation) {
        if (!operation.startsWith("login.")) {
            return siteDocumentPayload(operation);
        }
        SiteContracts.LoginSession session = new SiteContracts.LoginSession(
                LOGIN_SESSION_ID,
                SITE.id(),
                SITE.revision(),
                SITE.authorityRevision(),
                loginState(operation),
                NOW,
                NOW.plusSeconds(600),
                Optional.empty());
        return switch (operation) {
            case "login.begin", "login.status", "login.cancel" -> JSON.encode(session);
            case "login.list" -> JSON.encode(new SiteContracts.LoginSessionList(List.of(session), true));
            case "login.save" ->
                JSON.encode(
                        new SiteContracts.LoginSaveResult(SiteContracts.Projection.from(SAVED_SITE), true, session));
            default -> throw new AssertionError("unexpected Site operation " + operation);
        };
    }

    private static CanonicalPayload siteDocumentPayload(String operation) {
        return switch (operation) {
            case "read", "site/create" -> JSON.encode(SiteContracts.Projection.from(SITE));
            case "site/update",
                    "site/credential/bind",
                    "site/credential/clear",
                    "site/privateNetwork/bind",
                    "site/privateNetwork/clear" -> JSON.encode(SiteContracts.Projection.from(SAVED_SITE));
            case "list" -> page(SiteContracts.Projection.from(SITE));
            case "delete" -> JSON.encode(new DocumentContracts.Deleted("docs"));
            case "search" -> JSON.encode(new SiteContracts.SearchResult(List.of(SiteContracts.Projection.from(SITE))));
            default -> throw new AssertionError("unexpected Site operation " + operation);
        };
    }

    private static SiteContracts.LoginSessionState loginState(String operation) {
        return switch (operation) {
            case "login.cancel" -> SiteContracts.LoginSessionState.CANCELLED;
            case "login.save" -> SiteContracts.LoginSessionState.SAVED;
            default -> SiteContracts.LoginSessionState.READY;
        };
    }

    private static CanonicalPayload page(Object value) {
        return JSON.encode(new DocumentContracts.Page(List.of(JSON.encode(value)), "next"));
    }

    private static long resultRevision(ExtensionRpcContracts.CallPayload call) {
        if (Set.of("list", "proposal/list", "draft/list", "published/list").contains(call.operation())) {
            return 0;
        }
        if (call.extensionId().equals(BuiltinExtensionIds.SKILL)
                && Set.of("publish", "enable", "published/read", "skill/export").contains(call.operation())) {
            return 2;
        }
        if (call.extensionId().equals(BuiltinExtensionIds.KNOWLEDGE)
                && call.operation().equals("source/import")) {
            return 2;
        }
        if (call.extensionId().equals(BuiltinExtensionIds.SITE)
                && Set.of(
                                "site/update",
                                "site/credential/bind",
                                "site/credential/clear",
                                "site/privateNetwork/bind",
                                "site/privateNetwork/clear",
                                "login.save",
                                "delete")
                        .contains(call.operation())) {
            return 2;
        }
        return 1;
    }

    private static SiteManagementContracts.SaveRequest siteRequest() {
        return new SiteManagementContracts.SaveRequest(
                "docs",
                "Docs",
                SITE_ORIGIN,
                List.of(new SiteManagementContracts.AllowedOrigin("origin-1", SITE_ORIGIN)),
                true);
    }
}
