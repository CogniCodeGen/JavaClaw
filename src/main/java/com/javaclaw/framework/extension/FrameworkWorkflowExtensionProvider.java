package com.javaclaw.framework.extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.AgentClient;
import com.javaclaw.framework.api.ToolClient;
import com.javaclaw.framework.spi.JsonSchemaValidator;
import com.javaclaw.framework.spi.ExtensionLock;
import com.javaclaw.framework.spi.WorkflowNodeContribution;
import com.javaclaw.framework.spi.WorkflowNodeInvocation;
import com.javaclaw.framework.spi.WorkflowNodeResult;
import com.javaclaw.framework.spi.WorkflowTemplateContribution;
import com.javaclaw.workflow.model.GraphDefinition;
import com.javaclaw.workflow.model.StatePatch;
import com.javaclaw.workflow.node.StatePathValidator;
import com.javaclaw.workflow.runtime.NodeExecutionContext;
import com.javaclaw.workflow.runtime.NodeExecutor;
import com.javaclaw.workflow.runtime.NodeResult;
import com.javaclaw.workflow.runtime.WorkflowExtensionPlan;
import com.javaclaw.workflow.runtime.WorkflowExtensionPlanProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Adapts trusted workflow-node SPI contributions to the deterministic graph runtime. */
public final class FrameworkWorkflowExtensionProvider implements WorkflowExtensionPlanProvider {
    private final ExtensionManager extensions;
    private final AgentClient agents;
    private final ToolClient tools;
    private final JsonSchemaValidator schemas = new JsonSchemaValidator();

    public FrameworkWorkflowExtensionProvider(
            ExtensionManager extensions, AgentClient agents, ToolClient tools) {
        this.extensions = Objects.requireNonNull(extensions, "extensions");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.tools = Objects.requireNonNull(tools, "tools");
    }

    @Override
    public WorkflowExtensionPlan compile(GraphDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        ExtensionRegistrySnapshot.SnapshotLease lease = extensions.acquireCurrent();
        try {
            ExtensionRegistrySnapshot snapshot = lease.snapshot();
            Set<String> requiredTypes = new LinkedHashSet<>();
            definition.nodes().forEach(node -> requiredTypes.add(node.executorType()));
            LinkedHashMap<String, String> roots = new LinkedHashMap<>();
            snapshot.contributions().workflowNodes().stream()
                    .filter(owned -> requiredTypes.contains(owned.value().type()))
                    .forEach(owned -> roots.put(owned.extensionId(), "*"));
            if (roots.isEmpty()) {
                lease.close();
                return WorkflowExtensionPlan.empty();
            }
            List<ExtensionLock> locks = snapshot.resolveClosure(roots);
            ExtensionContributions selected = snapshot.contributionsFor(locks);
            return plan(snapshot.generation(), locks, selected.workflowNodes(), lease);
        } catch (RuntimeException failure) {
            lease.close();
            throw failure;
        }
    }

    @Override
    public Optional<WorkflowExtensionPlan> restore(List<ExtensionLock> locks) {
        List<ExtensionLock> exact = List.copyOf(locks == null ? List.of() : locks);
        if (exact.isEmpty()) return Optional.of(WorkflowExtensionPlan.empty());
        return extensions.acquireLocked(exact).map(lease -> {
            try {
                ExtensionRegistrySnapshot snapshot = lease.snapshot();
                ExtensionContributions selected = snapshot.contributionsFor(exact);
                return plan(snapshot.generation(), exact, selected.workflowNodes(), lease);
            } catch (RuntimeException failure) {
                lease.close();
                throw failure;
            }
        });
    }

    @Override
    public Optional<NodeExecutor> currentExecutor(String type) {
        return extensions.currentSnapshot().contributions().workflowNodes().stream()
                .map(OwnedContribution::value)
                .filter(value -> value.type().equals(type))
                .findFirst().map(this::adapt);
    }

    @Override
    public List<WorkflowTemplateContribution> currentTemplates() {
        return extensions.currentSnapshot().contributions().workflowTemplates().stream()
                .map(OwnedContribution::value).toList();
    }

    private WorkflowExtensionPlan plan(
            long generation,
            List<ExtensionLock> locks,
            List<OwnedContribution<WorkflowNodeContribution>> contributions,
            AutoCloseable lease) {
        LinkedHashMap<String, NodeExecutor> executors = new LinkedHashMap<>();
        for (OwnedContribution<WorkflowNodeContribution> owned : contributions) {
            NodeExecutor previous = executors.putIfAbsent(
                    owned.value().type(), adapt(owned.value()));
            if (previous != null) {
                throw new IllegalStateException(
                        "duplicate workflow node in exact plan: " + owned.value().type());
            }
        }
        return new WorkflowExtensionPlan(generation, locks, executors, lease);
    }

    private NodeExecutor adapt(WorkflowNodeContribution contribution) {
        return new NodeExecutor() {
            @Override public String type() { return contribution.type(); }

            @Override
            public List<String> validate(com.javaclaw.workflow.model.NodeDefinition node) {
                return schemas.validate(contribution.configurationSchema(), node.config(), "")
                        .stream().map(issue -> issue.path() + ": " + issue.message()).toList();
            }

            @Override
            public NodeResult execute(NodeExecutionContext context) throws Exception {
                WorkflowNodeResult result = Objects.requireNonNull(
                        contribution.handler().execute(new WorkflowNodeInvocation(
                                context.runId(), context.node().id(), context.node().config(),
                                context.state().toObjectNode(), agents, tools,
                                context.cancellation())),
                        "workflow extension node result");
                StatePatch.Builder patch = StatePatch.builder();
                var fields = result.setValues().fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (!StatePathValidator.isValid(field.getKey())) {
                        throw new IllegalArgumentException(
                                "extension node returned an invalid state path: " + field.getKey());
                    }
                    patch.setJson(field.getKey(), field.getValue());
                }
                NodeResult.Interrupt interrupt = result.interruptPrompt().isBlank() ? null
                        : new NodeResult.Interrupt(
                                result.interruptPrompt(), result.interruptResponseKey());
                String output = result.output().isBlank() ? null : result.output();
                return new NodeResult(patch.build(), output, interrupt);
            }
        };
    }
}
