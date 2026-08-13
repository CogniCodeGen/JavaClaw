package com.javaclaw.framework.builtin;

import com.javaclaw.framework.api.AgentDefinitionRef;
import com.javaclaw.framework.api.ModelPolicyRefs;
import com.javaclaw.framework.api.RunProfileRef;
import com.javaclaw.framework.extension.ExtensionManager;
import com.javaclaw.framework.spi.DefinitionProvisioner;
import com.javaclaw.framework.store.JdbcAgentDefinitionStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Workspace-scoped built-in definition leases used for deterministic startup and self-repair. */
public final class BuiltinDefinitionRegistry implements DefinitionProvisioner {
    private final Map<String, LinkedHashMap<String, BuiltinDefinitionBootstrap>> workspaces =
            new LinkedHashMap<>();

    public Registration register(
            String workspaceId,
            JdbcAgentDefinitionStore definitions,
            ModelPolicyRefs models,
            ExtensionManager extensions) {
        String workspace = requireWorkspace(workspaceId);
        BuiltinDefinitionBootstrap bootstrap = new BuiltinDefinitionBootstrap(
                workspace, definitions, models, extensions);
        String lease = UUID.randomUUID().toString();
        synchronized (this) {
            workspaces.computeIfAbsent(workspace, ignored -> new LinkedHashMap<>())
                    .put(lease, bootstrap);
        }
        return () -> remove(workspace, lease);
    }

    @Override
    public boolean ensureAgent(String workspaceId, AgentDefinitionRef reference) {
        Objects.requireNonNull(reference, "reference");
        if (reference.version() != null
                || !BuiltinDefinitionBootstrap.SYSTEM_AGENT_ID.equals(reference.id())) {
            return false;
        }
        return ensure(requireWorkspace(workspaceId));
    }

    @Override
    public boolean ensureProfile(String workspaceId, RunProfileRef reference) {
        Objects.requireNonNull(reference, "reference");
        if (reference.version() != null
                || !BuiltinDefinitionBootstrap.SYSTEM_PROFILE_IDS.contains(reference.id())) {
            return false;
        }
        return ensure(requireWorkspace(workspaceId));
    }

    private synchronized boolean ensure(String workspaceId) {
        LinkedHashMap<String, BuiltinDefinitionBootstrap> leases = workspaces.get(workspaceId);
        BuiltinDefinitionBootstrap bootstrap = last(leases);
        if (bootstrap == null) return false;
        bootstrap.ensureInstalled();
        return true;
    }

    private synchronized void remove(String workspaceId, String lease) {
        LinkedHashMap<String, BuiltinDefinitionBootstrap> leases = workspaces.get(workspaceId);
        if (leases == null) return;
        leases.remove(lease);
        if (leases.isEmpty()) workspaces.remove(workspaceId);
    }

    private static <K, V> V last(LinkedHashMap<K, V> values) {
        if (values == null || values.isEmpty()) return null;
        V result = null;
        for (V value : values.values()) result = value;
        return result;
    }

    private static String requireWorkspace(String value) {
        value = Objects.requireNonNull(value, "workspaceId").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("workspaceId must not be blank");
        return value;
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
