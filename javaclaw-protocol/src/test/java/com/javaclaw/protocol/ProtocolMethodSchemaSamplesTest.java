package com.javaclaw.protocol;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentProfileRef;
import com.javaclaw.api.AgentProfileSpec;
import com.javaclaw.api.ApprovalDecision;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.PermissionPresetInstantiationRequest;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderAdapter;
import com.javaclaw.api.ProviderAdapterOptions;
import com.javaclaw.api.ProviderAuthentication;
import com.javaclaw.api.ProviderEndpointSpec;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelDiscoveryRequest;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ThreadId;
import com.javaclaw.api.TurnBudget;
import com.javaclaw.api.TurnId;
import com.javaclaw.api.WorkspaceId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolMethodSchemaSamplesTest {
    private static final WorkspaceId WORKSPACE_ID = WorkspaceId.parse("00000000-0000-0000-0000-000000000001");
    private static final ThreadId THREAD_ID = ThreadId.parse("00000000-0000-0000-0000-000000000002");
    private static final TurnId TURN_ID = TurnId.parse("00000000-0000-0000-0000-000000000003");
    private final CanonicalJson json = new CanonicalJson();

    @Test
    void initialize实际编码匹配封闭Schema() throws Exception {
        InitializeParams params = new InitializeParams(
                2,
                new ClientInfo("Desktop", "5.0.0"),
                new CapabilityAdvertisement(Set.of("items"), Set.of("native-compaction")));

        assertClosedShape(json.encode(params), "initialization-v2.schema.json", "/$defs/initializeParams");
    }

    @Test
    void turn审批与Rollout写信封匹配逐方法Schema() throws Exception {
        assertCommand(
                new CoreRpcContracts.TurnStartPayload(THREAD_ID, Optional.of(new AgentProfileRef("coding", 2)), "修复测试"),
                4,
                "core-interaction-v2.schema.json",
                "/$defs/turnStartCommand");
        assertCommand(
                new CoreRpcContracts.ApprovalResolvePayload("approval-1", ApprovalDecision.APPROVED, "已确认"),
                2,
                "core-interaction-v2.schema.json",
                "/$defs/approvalResolveCommand");
        assertCommand(
                new CoreRpcContracts.RolloutExportPayload(THREAD_ID, Path.of("/tmp/rollout.jsonl")),
                7,
                "core-interaction-v2.schema.json",
                "/$defs/rolloutExportCommand");
    }

    @Test
    void provider与Profile实际编码匹配逐方法Schema() throws Exception {
        assertCommand(
                new ProviderProfileRpcContracts.ProviderCreatePayload(
                        "cloud", providerSpec(), ProviderLifecycle.DISABLED),
                0,
                "provider-profile-v2.schema.json",
                "/$defs/providerCreateCommand");
        assertCommand(
                new ProviderProfileRpcContracts.AgentProfileCreatePayload("coding", profileSpec()),
                0,
                "provider-profile-v2.schema.json",
                "/$defs/profileCreateCommand");
        assertCommand(
                new ProviderProfileRpcContracts.ProfileBindingUpdatePayload(
                        WORKSPACE_ID, Optional.of(THREAD_ID), new AgentProfileRef("coding", 1)),
                0,
                "provider-profile-v2.schema.json",
                "/$defs/bindingUpdateCommand");
    }

    @Test
    void Provider发现Embedding绑定和权限预设匹配逐方法Schema() throws Exception {
        ProviderRef embedding = new ProviderRef("cloud", 2, "embedding");
        PermissionPresetInstantiationRequest permissionRequest = new PermissionPresetInstantiationRequest(
                "workspace-review", 1, WORKSPACE_ID, "review-profile", Set.of("read_file"), Set.of());

        assertClosedShape(
                json.encode(new ProviderModelDiscoveryRequest("cloud", 2)),
                "provider-profile-v2.schema.json",
                "/$defs/providerModelDiscoveryRequest");
        assertCommand(
                new ProviderModelDiscoveryRequest("cloud", 2),
                2,
                "provider-profile-v2.schema.json",
                "/$defs/providerModelDiscoveryStartCommand");
        assertClosedShape(
                json.encode(new ProviderModelDiscoveryRpcContracts.ReadPayload("11111111-1111-1111-1111-111111111111")),
                "provider-profile-v2.schema.json",
                "/$defs/providerModelDiscoveryReadParams");
        assertCommand(
                new ProviderModelDiscoveryRpcContracts.CancelPayload(
                        "11111111-1111-1111-1111-111111111111", ProviderModelDiscoveryRpcContracts.CLIENT_CANCELLED),
                1,
                "provider-profile-v2.schema.json",
                "/$defs/providerModelDiscoveryCancelCommand");
        assertCommand(
                new ProviderProfileRpcContracts.EmbeddingBindingUpdatePayload(embedding),
                0,
                "provider-profile-v2.schema.json",
                "/$defs/embeddingBindingUpdateCommand");
        assertCommand(
                new PermissionProfileRpcContracts.PresetInstantiatePayload(permissionRequest),
                0,
                "permission-profile-v2.schema.json",
                "/$defs/permissionPresetInstantiateCommand");
    }

    @Test
    void extension事件与工具查询实际编码使用标量标识() throws Exception {
        ExtensionRpcContracts.CallPayload call = new ExtensionRpcContracts.CallPayload(
                "plan", WORKSPACE_ID, Optional.of(THREAD_ID), Optional.of(TURN_ID), "definition.read", empty());
        ExtensionRpcContracts.ExtensionEvent event = new ExtensionRpcContracts.ExtensionEvent(
                WORKSPACE_ID, "plan", "workspace", WORKSPACE_ID.toString(), "definition.put", 5);
        ToolRpcContracts.CatalogQuery toolQuery =
                new ToolRpcContracts.CatalogQuery(WORKSPACE_ID, "standard", 1, "file", 20);

        assertClosedShape(json.encode(call), "extension-v2.schema.json", "/$defs/callPayload");
        assertClosedShape(json.encode(event), "extension-v2.schema.json", "/$defs/extensionEvent");
        assertClosedShape(json.encode(toolQuery), "tool-v2.schema.json", "/$defs/catalogQuery");
        assertEquals(
                WORKSPACE_ID.toString(),
                json.textField(json.encode(event), "workspaceId").orElseThrow());
    }

    private void assertCommand(Object payload, long revision, String resource, String pointer) throws Exception {
        CanonicalPayload encodedPayload = json.encode(payload);
        CanonicalPayload command = json.encode(new WriteCommand("sample-command", revision, encodedPayload));
        JsonNode commandSchema = schema(resource).at(pointer);

        assertClosedShape(command, commandSchema, resource);
        assertClosedShape(encodedPayload, commandSchema.path("properties").path("payload"), resource);
    }

    private void assertClosedShape(CanonicalPayload actual, String resource, String pointer) throws Exception {
        assertClosedShape(actual, schema(resource).at(pointer), resource);
    }

    private void assertClosedShape(CanonicalPayload actual, JsonNode sourceSchema, String resource) throws Exception {
        JsonNode schema = dereference(sourceSchema, resource);
        assertFalse(schema.isMissingNode(), resource);
        assertTrue(schema.path("additionalProperties").isBoolean(), resource);
        assertFalse(schema.path("additionalProperties").booleanValue(), resource);
        assertEquals(required(schema), json.fieldNames(actual), resource);
    }

    private JsonNode dereference(JsonNode schema, String currentResource) throws Exception {
        if (!schema.has("$ref")) {
            return schema;
        }
        String reference = schema.path("$ref").textValue();
        int fragment = reference.indexOf('#');
        String file = fragment <= 0 ? currentResource : reference.substring(0, fragment);
        String pointer = fragment < 0 ? "" : reference.substring(fragment + 1);
        return schema(file).at(pointer);
    }

    private JsonNode schema(String resource) throws IOException {
        try (var input = ProtocolMethodSchemaSamplesTest.class.getResourceAsStream("/schema/" + resource)) {
            if (input == null) {
                throw new IOException("schema resource not found: " + resource);
            }
            return json.mapper().readTree(input);
        }
    }

    private static Set<String> required(JsonNode schema) {
        HashSet<String> result = new HashSet<>();
        schema.path("required").forEach(value -> result.add(value.textValue()));
        return Set.copyOf(result);
    }

    private CanonicalPayload empty() {
        return json.parse("{}");
    }

    private static ProviderEndpointSpec providerSpec() {
        return new ProviderEndpointSpec(
                "OpenAI",
                ProviderAdapter.OPENAI_COMPATIBLE,
                Optional.of(URI.create("https://api.example.test/v1")),
                ProviderAuthentication.API_KEY,
                List.of(new ProviderModelSpec(
                        "model", "Model", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty())),
                Optional.empty(),
                Duration.ofSeconds(30),
                1,
                new ProviderAdapterOptions.OpenAiCompatible(Optional.of("example"), Optional.empty()));
    }

    private static AgentProfileSpec profileSpec() {
        return new AgentProfileSpec(
                "Coding",
                "保持代码清晰。",
                new ProviderRef("cloud", 1, "model"),
                new PermissionProfileRef("standard", 1),
                Set.of("read_file"),
                new TurnBudget(10_000, 2_000, 20, 2, Duration.ofMinutes(5)));
    }
}
