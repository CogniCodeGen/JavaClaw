package com.javaclaw.framework.core;

import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.spi.RunResourceRegistry;
import com.javaclaw.framework.spi.RunResourceScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** In-process Run resource owner; durable state remains the responsibility of RunStore. */
public final class DefaultRunResourceRegistry implements RunResourceRegistry, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DefaultRunResourceRegistry.class);

    private final ConcurrentHashMap<RunId, Scope> scopes = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public RunResourceScope forRun(RunId runId) {
        Objects.requireNonNull(runId, "runId");
        if (closed.get()) throw new IllegalStateException("Run resource registry is closed");
        return scopes.computeIfAbsent(runId, ignored -> new Scope());
    }

    @Override
    public void release(RunId runId) {
        if (runId == null) return;
        Scope scope = scopes.remove(runId);
        if (scope != null) scope.close(runId);
    }

    int activeScopeCount() {
        return scopes.size();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        List<RunId> ids = new ArrayList<>(scopes.keySet());
        ids.forEach(this::release);
    }

    private static final class Scope implements RunResourceScope {
        private final ConcurrentHashMap<String, Object> resources = new ConcurrentHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public <T> T getOrCreate(String key, Class<T> type, Supplier<? extends T> factory) {
            String normalizedKey = Objects.requireNonNull(key, "key").strip();
            if (normalizedKey.isEmpty()) throw new IllegalArgumentException("resource key is blank");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(factory, "factory");
            if (closed.get()) throw new IllegalStateException("Run resource scope is closed");
            Object value = resources.computeIfAbsent(normalizedKey, ignored ->
                    Objects.requireNonNull(factory.get(), "run resource"));
            if (!type.isInstance(value)) {
                throw new IllegalStateException("Run resource '" + normalizedKey
                        + "' was registered as " + value.getClass().getName()
                        + ", not " + type.getName());
            }
            return type.cast(value);
        }

        private void close(RunId runId) {
            if (!closed.compareAndSet(false, true)) return;
            List<Object> values = new ArrayList<>(resources.values());
            resources.clear();
            for (int index = values.size() - 1; index >= 0; index--) {
                if (!(values.get(index) instanceof AutoCloseable closeable)) continue;
                try {
                    closeable.close();
                } catch (Exception failure) {
                    log.warn("Could not close Run-scoped resource for {}", runId, failure);
                }
            }
        }
    }
}
