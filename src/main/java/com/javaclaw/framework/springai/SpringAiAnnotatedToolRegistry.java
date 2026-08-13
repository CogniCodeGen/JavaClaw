package com.javaclaw.framework.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.spi.FrameworkTool;
import com.javaclaw.framework.spi.ToolContext;
import com.javaclaw.framework.spi.ToolDescriptor;
import com.javaclaw.framework.spi.ToolExecutionContext;
import com.javaclaw.framework.spi.ToolObjectSource;
import com.javaclaw.framework.spi.ToolObjectBundle;
import com.javaclaw.framework.spi.ToolProviderFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Workspace-scoped bridge for existing and future Spring AI {@code @Tool} objects. The registry
 * contributes a single provider SPI to the framework; each call creates fresh tool objects and
 * every callback is wrapped as a {@link FrameworkTool}, so invocation cannot bypass the gateway.
 */
public final class SpringAiAnnotatedToolRegistry implements ToolProviderFactory {
    private final ObjectMapper json;
    private final Map<String, ToolObjectFactory> workspaces = new ConcurrentHashMap<>();

    public SpringAiAnnotatedToolRegistry(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    public Registration register(String workspaceId, ToolObjectFactory factory) {
        String key = required(workspaceId);
        Objects.requireNonNull(factory, "factory");
        ToolObjectFactory previous = workspaces.putIfAbsent(key, factory);
        if (previous != null) {
            throw new IllegalStateException("workspace tool provider already registered: " + key);
        }
        return () -> workspaces.remove(key, factory);
    }

    /**
     * Validates every known host tool type without constructing it. Workspace
     * configuration calls this during context creation so an authorization gap
     * fails startup instead of remaining latent until the first Run.
     */
    public static void validateContracts(Iterable<Class<?>> toolTypes) {
        Objects.requireNonNull(toolTypes, "toolTypes");
        for (Class<?> toolType : toolTypes) {
            Class<?> checked = Objects.requireNonNull(toolType, "tool type");
            if (hasToolMethod(checked)) contracts(checked);
        }
    }

    @Override
    public List<FrameworkTool> create(ToolContext context) {
        ToolObjectFactory factory = workspaces.get(context.scope().workspaceId());
        if (factory == null) return List.of();
        ToolObjectBundle bundle = Objects.requireNonNull(factory.create(context), "tool object bundle");
        try {
            List<ContractedCallback> callbacks = callbacks(flatten(bundle.objects()));
            if (callbacks.isEmpty()) {
                close(bundle.lifecycle());
                return List.of();
            }
            SharedLifecycle lifecycle = new SharedLifecycle(bundle.lifecycle(), callbacks.size());
            List<FrameworkTool> tools = new ArrayList<>(callbacks.size());
            for (ContractedCallback callback : callbacks) {
                tools.add(new AnnotatedFrameworkTool(
                        callback.callback(), callback.contract(), lifecycle, json));
            }
            assertUnique(tools);
            return List.copyOf(tools);
        } catch (RuntimeException failure) {
            close(bundle.lifecycle());
            throw failure;
        }
    }

    private List<Object> flatten(List<Object> roots) {
        List<Object> values = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object root : roots) flatten(root, values, visited);
        return values;
    }

    private void flatten(Object value, List<Object> result, Set<Object> visited) {
        if (value == null || !visited.add(value)) return;
        if (value instanceof ToolObjectSource source) {
            source.toolObjects().forEach(child -> flatten(child, result, visited));
        } else {
            result.add(value);
        }
    }

    private List<ContractedCallback> callbacks(List<Object> objects) {
        List<ContractedCallback> callbacks = new ArrayList<>();
        for (Object object : objects) {
            if (object instanceof ToolCallback callback) {
                callbacks.add(new ContractedCallback(callback, externalContract()));
            } else if (object instanceof ToolCallbackProvider provider) {
                for (ToolCallback callback : provider.getToolCallbacks()) {
                    callbacks.add(new ContractedCallback(callback, externalContract()));
                }
            } else if (hasToolMethod(object)) {
                Map<String, com.javaclaw.framework.spi.ToolContract> contracts =
                        contracts(object.getClass());
                for (ToolCallback callback : MethodToolCallbackProvider.builder()
                        .toolObjects(object).build().getToolCallbacks()) {
                    String name = callback.getToolDefinition().name();
                    var contract = contracts.get(name);
                    if (contract == null) {
                        throw new IllegalStateException(
                                "host @Tool is missing @ToolContract: " + name);
                    }
                    callbacks.add(new ContractedCallback(callback, contract));
                }
            }
        }
        return callbacks;
    }

    private static Map<String, com.javaclaw.framework.spi.ToolContract> contracts(Class<?> toolType) {
        Map<String, com.javaclaw.framework.spi.ToolContract> result = new java.util.HashMap<>();
        var classContract = org.springframework.core.annotation.AnnotationUtils.findAnnotation(
                toolType, com.javaclaw.framework.spi.ToolContract.class);
        for (Method method : toolType.getDeclaredMethods()) {
            var tool = org.springframework.core.annotation.AnnotationUtils.findAnnotation(
                    method, org.springframework.ai.tool.annotation.Tool.class);
            if (tool == null) continue;
            String name = tool.name().isBlank() ? method.getName() : tool.name();
            var contract = org.springframework.core.annotation.AnnotationUtils.findAnnotation(
                    method, com.javaclaw.framework.spi.ToolContract.class);
            if (contract == null) contract = classContract;
            if (contract == null) {
                throw new IllegalStateException(
                        "host @Tool is missing @ToolContract: " + toolType.getName() + "#" + name);
            }
            validateContract(toolType, name, contract);
            result.put(name, contract);
        }
        return result;
    }

    private static void validateContract(
            Class<?> toolType, String toolName,
            com.javaclaw.framework.spi.ToolContract contract) {
        if (contract.group() == null || contract.group().isBlank()) {
            throw new IllegalStateException("host @Tool has a blank group: "
                    + toolType.getName() + "#" + toolName);
        }
        if (contract.permissions() == null || contract.permissions().length == 0) {
            throw new IllegalStateException("host @Tool has no permissions: "
                    + toolType.getName() + "#" + toolName);
        }
        try {
            PermissionSet.of(contract.permissions());
        } catch (RuntimeException failure) {
            throw new IllegalStateException("host @Tool has invalid permissions: "
                    + toolType.getName() + "#" + toolName, failure);
        }
    }

    private static com.javaclaw.framework.spi.ToolContract externalContract() {
        return ExternalContractHolder.class.getAnnotation(
                com.javaclaw.framework.spi.ToolContract.class);
    }

    @com.javaclaw.framework.spi.ToolContract(
            group = "extension", permissions = {"tool.execute"}, idempotent = false)
    private static final class ExternalContractHolder { }

    private record ContractedCallback(
            ToolCallback callback, com.javaclaw.framework.spi.ToolContract contract) { }

    private static boolean hasToolMethod(Object object) {
        return hasToolMethod(object.getClass());
    }

    private static boolean hasToolMethod(Class<?> toolType) {
        for (Method method : toolType.getDeclaredMethods()) {
            if (org.springframework.core.annotation.AnnotationUtils.findAnnotation(
                    method, org.springframework.ai.tool.annotation.Tool.class) != null) return true;
        }
        return false;
    }

    private static void assertUnique(List<FrameworkTool> tools) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (FrameworkTool tool : tools) {
            if (!names.add(tool.descriptor().name())) {
                throw new IllegalStateException("duplicate workspace tool: " + tool.descriptor().name());
            }
        }
    }

    private static String required(String value) {
        value = Objects.requireNonNull(value, "workspaceId").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("workspaceId must not be blank");
        return value;
    }

    private static void close(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception failure) {
            throw new IllegalStateException("cannot close annotated tool bundle", failure);
        }
    }

    @FunctionalInterface
    public interface ToolObjectFactory {
        ToolObjectBundle create(ToolContext context);
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private static final class AnnotatedFrameworkTool implements FrameworkTool {
        private final ToolCallback callback;
        private final SharedLifecycle lifecycle;
        private final ObjectMapper json;
        private final ToolDescriptor descriptor;
        private final AtomicBoolean closed = new AtomicBoolean();

        private AnnotatedFrameworkTool(
                ToolCallback callback,
                com.javaclaw.framework.spi.ToolContract contract,
                SharedLifecycle lifecycle,
                ObjectMapper json) {
            this.callback = callback;
            this.lifecycle = lifecycle;
            this.json = json;
            var definition = callback.getToolDefinition();
            JsonNode schema;
            try {
                schema = json.readTree(definition.inputSchema());
            } catch (Exception failure) {
                throw new IllegalStateException("invalid Spring AI tool schema: "
                        + definition.name(), failure);
            }
            descriptor = new ToolDescriptor(definition.name(), definition.description(), schema,
                    contract.group(), PermissionSet.of(contract.permissions()),
                    contract.idempotent());
        }

        @Override
        public ToolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public JsonNode execute(JsonNode arguments, ToolExecutionContext context) throws Exception {
            String result = callback.call(json.writeValueAsString(arguments));
            if (result == null) return TextNode.valueOf("");
            try {
                JsonNode parsed = json.readTree(result);
                return parsed == null ? TextNode.valueOf(result) : parsed;
            } catch (Exception ignored) {
                return TextNode.valueOf(result);
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) lifecycle.release();
        }

    }

    private static final class SharedLifecycle {
        private final AutoCloseable lifecycle;
        private final AtomicInteger remaining;

        private SharedLifecycle(AutoCloseable lifecycle, int references) {
            this.lifecycle = lifecycle;
            this.remaining = new AtomicInteger(references);
        }

        private void release() {
            if (remaining.decrementAndGet() == 0) close(lifecycle);
        }
    }
}
