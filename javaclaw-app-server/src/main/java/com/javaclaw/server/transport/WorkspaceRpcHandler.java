package com.javaclaw.server.transport;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.javaclaw.agent.runtime.WorkspaceUseCases;
import com.javaclaw.core.api.WorkspaceId;
import com.javaclaw.protocol.RpcMethods;

/** JSON-RPC adapter for Workspace use cases. */
final class WorkspaceRpcHandler implements RpcHandler {
    private static final Set<String> METHODS = Set.of(
            RpcMethods.WORKSPACE_LIST,
            RpcMethods.WORKSPACE_CREATE,
            RpcMethods.WORKSPACE_READ,
            RpcMethods.WORKSPACE_UPDATE,
            RpcMethods.WORKSPACE_DELETE,
            RpcMethods.WORKSPACE_INSTRUCTIONS_RESOLVE);

    private final WorkspaceUseCases workspaces;
    private final com.javaclaw.agent.prompt.AgentsInstructionUseCases instructions;
    private final ObjectMapper json;
    private final ProtocolMapper wire;

    WorkspaceRpcHandler(
            WorkspaceUseCases workspaces,
            com.javaclaw.agent.prompt.AgentsInstructionUseCases instructions,
            ObjectMapper json,
            ProtocolMapper wire) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.instructions = instructions;
        this.json = Objects.requireNonNull(json, "json");
        this.wire = Objects.requireNonNull(wire, "wire");
    }

    @Override
    public Set<String> methods() {
        return METHODS;
    }

    @Override
    public JsonNode handle(String method, JsonNode params) {
        return switch (method) {
            case RpcMethods.WORKSPACE_INSTRUCTIONS_RESOLVE -> resolveInstructions(params);
            case RpcMethods.WORKSPACE_LIST ->
                json.valueToTree(workspaces.listWorkspaces().stream()
                        .map(wire::workspace)
                        .toList());
            case RpcMethods.WORKSPACE_CREATE ->
                json.valueToTree(wire.workspace(workspaces.createWorkspace(
                        RequestParameters.requiredText(params, "name"),
                        RequestParameters.requiredAbsolutePath(params, "root"),
                        RequestParameters.optionalText(params, "idempotencyKey", null))));
            case RpcMethods.WORKSPACE_READ -> {
                WorkspaceId id = id(params);
                yield workspaces
                        .readWorkspace(id)
                        .map(wire::workspace)
                        .<JsonNode>map(json::valueToTree)
                        .orElseThrow(() -> new NoSuchElementException("workspace not found: " + id));
            }
            case RpcMethods.WORKSPACE_UPDATE -> {
                WorkspaceId id = id(params);
                yield json.valueToTree(wire.workspace(workspaces.updateWorkspace(
                        id,
                        RequestParameters.requiredText(params, "name"),
                        RequestParameters.optionalLong(params, "expectedRevision", -1),
                        RequestParameters.optionalText(params, "idempotencyKey", null))));
            }
            case RpcMethods.WORKSPACE_DELETE -> {
                workspaces.deleteWorkspace(
                        id(params),
                        RequestParameters.optionalLong(params, "expectedRevision", -1),
                        RequestParameters.optionalText(params, "idempotencyKey", null));
                yield RpcResults.flag("deleted", true);
            }
            default -> throw new RpcRouter.MethodNotFound(method);
        };
    }

    private static WorkspaceId id(JsonNode params) {
        return new WorkspaceId(RequestParameters.requiredText(params, "workspaceId"));
    }

    private com.javaclaw.agent.prompt.AgentsInstructionUseCases requireInstructions() {
        if (instructions == null) {
            throw new IllegalStateException("AGENTS.md resolution is unavailable");
        }
        return instructions;
    }

    private JsonNode resolveInstructions(JsonNode params) {
        WorkspaceId workspaceId = id(params);
        var workspace = workspaces
                .readWorkspace(workspaceId)
                .orElseThrow(() -> new NoSuchElementException("workspace not found: " + workspaceId));
        return json.valueToTree(
                wire.instructions(requireInstructions().inspect(workspace.root(), Set.of(workspace.root()))));
    }
}
