package com.javaclaw.builtin.extensions;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ApprovalPolicy;
import com.javaclaw.api.ExecutionOverrides;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.api.ReasoningPreference;
import com.javaclaw.builtin.contracts.MemoryV3Contracts;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryLearningSelectionViewTest {
    @Test
    void savedExecutionReturnsExactValuesInsteadOfReplacingThemWithFormDefaults() throws Exception {
        var fixture = new Fixture(true);
        var values = fixture.query("view.learning.definition", "learningDefinition");
        assertEquals("EVERY_CALL", values.get("approvalPolicy"));
        assertEquals("HIGH", values.get("reasoning"));
        assertEquals(Map.of("id", "saved-role", "revision", 7), values.get("role"));
        assertEquals(
                Map.of("endpointId", "saved-provider", "endpointRevision", 8, "model", "saved-model"),
                values.get("provider"));
        assertEquals(Map.of("id", "saved-permission", "version", 9), values.get("permissionProfile"));
        String explanation = values.get("savedExecution").toString();
        assertTrue(explanation.contains("saved-role"));
        assertTrue(explanation.contains("saved-provider/saved-model"));
        assertTrue(explanation.contains("失效"));
    }

    @Test
    void catalogHintsRetainMissingSavedVersionsWithoutOverridingCommandBindingValues() throws Exception {
        var fixture = new Fixture(true);
        assertSelection(fixture.query("view.learning.roles", "roles"), "roles", "saved-role", 7);
        assertSelection(
                fixture.query(AutomationSelectionView.PROVIDERS, "providers"),
                "providers",
                "saved-provider/saved-model",
                8);
        assertSelection(
                fixture.query(AutomationSelectionView.PERMISSIONS, "permissions"),
                "permissions",
                "saved-permission",
                9);
    }

    @Test
    void unsavedLearningConfigurationDoesNotInventCatalogSelectionOrExecutionValues() throws Exception {
        var fixture = new Fixture(false);
        assertTrue(fixture.query("view.learning.roles", "roles").isEmpty());
        assertTrue(fixture.query(AutomationSelectionView.PROVIDERS, "providers").isEmpty());
        assertTrue(fixture.query(AutomationSelectionView.PERMISSIONS, "permissions")
                .isEmpty());
        var values = fixture.query("view.learning.definition", "learningDefinition");
        assertFalse(values.containsKey("approvalPolicy"));
        assertFalse(values.containsKey("role"));
        assertEquals(false, values.get("enabled"));
    }

    private static void assertSelection(Map<?, ?> values, String source, String key, int revision) {
        assertEquals(
                Map.of(
                        "version",
                        1,
                        "dataSourceId",
                        source,
                        "key",
                        key,
                        "revisionField",
                        "revision",
                        "revision",
                        revision),
                values.get("view.initialSelection"));
        assertEquals(1, values.size());
    }

    private static final class Fixture {
        private final BuiltinExtensionTestSupport support = new BuiltinExtensionTestSupport();
        private final BuiltinExtensionTestSupport.Started memory = support.start(new MemoryExtension());

        private Fixture(boolean saved) throws Exception {
            if (saved) {
                var execution = new ExecutionOverrides(
                        Optional.of(new AgentRoleRef("saved-role", 7)),
                        Optional.of(new ProviderRef("saved-provider", 8, "saved-model")),
                        Optional.of(new PermissionProfileRef("saved-permission", 9)),
                        Optional.of(ApprovalPolicy.EVERY_CALL),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(ReasoningPreference.HIGH));
                support.store.inTransaction(MemoryStoreAccess.ID, transaction -> {
                    transaction.put(
                            MemoryLearningState.definitions(support.workspaceId),
                            MemoryLearningState.DEFINITION_ID,
                            0,
                            support.payloads.encode(new MemoryV3Contracts.LearningDefinition(
                                    MemoryLearningState.DEFINITION_ID,
                                    1,
                                    "学习",
                                    true,
                                    execution,
                                    BuiltinExtensionTestSupport.NOW,
                                    BuiltinExtensionTestSupport.NOW)));
                    return null;
                });
            }
        }

        private Map<?, ?> query(String operation, String source) throws Exception {
            var result = support.decode(
                    memory.query(support.request(
                            operation,
                            new ViewQueryRequest(source, Map.of(), "", 100, Optional.empty()),
                            Optional.empty(),
                            0)),
                    ViewQueryResult.class);
            return support.payloads.decode(result.values(), Map.class);
        }
    }
}
