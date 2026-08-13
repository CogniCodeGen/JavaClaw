package com.javaclaw.framework.springai;

import com.javaclaw.framework.spi.ModelTier;
import org.springframework.ai.chat.model.ChatModel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/** Process registry with workspace-isolated tier routing and generation-safe leases. */
public final class SpringAiModelRegistry {
    private static final String LEGACY_WORKSPACE = "__legacy__";
    private final Map<String, LinkedHashMap<String, ChatModel>> models = new LinkedHashMap<>();
    private final Map<RouteKey, LinkedHashMap<String, String>> routes = new LinkedHashMap<>();

    public synchronized void register(String policyRef, ChatModel model) {
        String id = requireId(policyRef);
        LinkedHashMap<String, ChatModel> stack = models.computeIfAbsent(
                id, ignored -> new LinkedHashMap<>());
        if (!stack.isEmpty()) throw new IllegalStateException("model policy already registered: " + id);
        stack.put("manual:" + id, Objects.requireNonNull(model, "model"));
    }

    public synchronized void registerOrReplace(String policyRef, ChatModel model) {
        String id = requireId(policyRef);
        LinkedHashMap<String, ChatModel> stack = models.computeIfAbsent(
                id, ignored -> new LinkedHashMap<>());
        stack.put("manual:" + id, Objects.requireNonNull(model, "model"));
    }

    public synchronized void unregister(String policyRef, ChatModel expected) {
        String id = requireId(policyRef);
        LinkedHashMap<String, ChatModel> stack = models.get(id);
        if (stack == null) return;
        stack.entrySet().removeIf(entry -> entry.getValue() == expected);
        if (stack.isEmpty()) models.remove(id);
        removeDanglingRoutes();
    }

    /** Atomically publishes a complete workspace generation; closing restores any older lease. */
    public synchronized Registration installWorkspace(
            String workspaceId,
            Map<String, ChatModel> registrations,
            Map<ModelTier, String> tierRoutes) {
        String workspace = requireWorkspace(workspaceId);
        Objects.requireNonNull(registrations, "registrations");
        Objects.requireNonNull(tierRoutes, "tierRoutes");
        LinkedHashMap<String, ChatModel> checkedModels = new LinkedHashMap<>();
        registrations.forEach((ref, model) -> checkedModels.put(
                requireId(ref), Objects.requireNonNull(model, "model")));
        LinkedHashMap<ModelTier, String> checkedRoutes = new LinkedHashMap<>();
        tierRoutes.forEach((tier, ref) -> {
            String id = requireId(ref);
            if (!checkedModels.containsKey(id) && !models.containsKey(id)) {
                throw new NoSuchElementException("model not registered: " + id);
            }
            checkedRoutes.put(Objects.requireNonNull(tier, "tier"), id);
        });
        String lease = UUID.randomUUID().toString();
        checkedModels.forEach((ref, model) -> models.computeIfAbsent(
                ref, ignored -> new LinkedHashMap<>()).put(lease, model));
        checkedRoutes.forEach((tier, ref) -> routes.computeIfAbsent(
                new RouteKey(workspace, tier), ignored -> new LinkedHashMap<>()).put(lease, ref));
        return () -> removeLease(lease);
    }

    public synchronized void route(String workspaceId, ModelTier tier, String policyRef) {
        String workspace = requireWorkspace(workspaceId);
        String id = requireId(policyRef);
        if (!models.containsKey(id)) throw new NoSuchElementException("model not registered: " + id);
        routes.computeIfAbsent(new RouteKey(workspace, Objects.requireNonNull(tier, "tier")),
                ignored -> new LinkedHashMap<>()).put("manual:" + workspace + ":" + tier, id);
    }

    public void route(ModelTier tier, String policyRef) {
        route(LEGACY_WORKSPACE, tier, policyRef);
    }

    public synchronized ChatModel require(String policyRef) {
        LinkedHashMap<String, ChatModel> stack = models.get(requireId(policyRef));
        ChatModel model = last(stack);
        if (model == null) {
            throw new NoSuchElementException("model policy not registered: " + policyRef);
        }
        return model;
    }

    public ChatModel require(String workspaceId, ModelTier tier) {
        return require(policyFor(workspaceId, tier));
    }

    public ChatModel require(ModelTier tier) {
        return require(LEGACY_WORKSPACE, tier);
    }

    public synchronized String policyFor(String workspaceId, ModelTier tier) {
        RouteKey key = new RouteKey(requireWorkspace(workspaceId),
                Objects.requireNonNull(tier, "tier"));
        String id = last(routes.get(key));
        if (id == null) {
            throw new NoSuchElementException(
                    "model tier not routed for workspace " + workspaceId + ": " + tier);
        }
        return id;
    }

    public String policyFor(ModelTier tier) {
        return policyFor(LEGACY_WORKSPACE, tier);
    }

    public synchronized int size() {
        return (int) models.values().stream().filter(stack -> !stack.isEmpty()).count();
    }

    private synchronized void removeLease(String lease) {
        models.values().forEach(stack -> stack.remove(lease));
        routes.values().forEach(stack -> stack.remove(lease));
        models.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        routes.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        removeDanglingRoutes();
    }

    private void removeDanglingRoutes() {
        routes.values().forEach(stack -> stack.entrySet().removeIf(
                entry -> !models.containsKey(entry.getValue())));
        routes.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    private static <K, V> V last(LinkedHashMap<K, V> values) {
        if (values == null || values.isEmpty()) return null;
        V result = null;
        for (V value : values.values()) result = value;
        return result;
    }

    private static String requireId(String value) {
        value = Objects.requireNonNull(value, "policyRef").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("model policy ref must not be blank");
        return value;
    }

    private static String requireWorkspace(String value) {
        value = Objects.requireNonNull(value, "workspaceId").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("workspaceId must not be blank");
        return value;
    }

    private record RouteKey(String workspaceId, ModelTier tier) { }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override void close();
    }
}
