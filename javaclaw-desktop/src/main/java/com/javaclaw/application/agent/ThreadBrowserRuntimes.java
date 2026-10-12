package com.javaclaw.application.agent;

import com.javaclaw.browser.PlaywrightBrowserManager;
import com.javaclaw.browser.BrowserInteractionState;
import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.spi.ThreadLifecycleListener;
import com.javaclaw.site.SiteCredentialManager;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Browser pages belong to a persistent Thread, while tool facades belong to its current Turn. */
public final class ThreadBrowserRuntimes implements ThreadLifecycleListener, AutoCloseable {
    private final String workspaceId;
    private final PlaywrightBrowserManager factory;
    private final java.util.function.Consumer<String> clearCredentials;
    private final Map<RunScope, BrowserRuntime> runtimes = new HashMap<>();
    private final Set<RunScope> deleted = new HashSet<>();
    private boolean closed;

    public ThreadBrowserRuntimes(String workspaceId, PlaywrightBrowserManager factory,
                                 SiteCredentialManager credentials) {
        this(workspaceId, factory, credentials::clearScopeBindings);
    }

    ThreadBrowserRuntimes(String workspaceId, PlaywrightBrowserManager factory,
                         java.util.function.Consumer<String> clearCredentials) {
        this.workspaceId = workspaceId;
        this.factory = factory;
        this.clearCredentials = clearCredentials;
    }

    public synchronized PlaywrightBrowserManager acquire(RunScope scope) {
        return acquireRuntime(scope).manager();
    }

    /** 工具外观可以重建；同一会话的页面、引用和操作锁必须共同保留。 */
    public synchronized BrowserRuntime acquireRuntime(RunScope scope) {
        if (!accepts(scope)) throw new SecurityException("browser workspace mismatch");
        if (closed || deleted.contains(scope)) throw new IllegalStateException("thread browser runtime is closed");
        return runtimes.computeIfAbsent(scope, key -> new BrowserRuntime(
                factory.createIsolated(scopeId(key)), new BrowserInteractionState()));
    }

    public record BrowserRuntime(PlaywrightBrowserManager manager, BrowserInteractionState interaction) {
        private void close() {
            RuntimeException failure = null;
            try {
                interaction.close();
            } catch (RuntimeException cleanupFailure) {
                failure = cleanupFailure;
            } finally {
                try {
                    manager.closePermanently();
                } catch (RuntimeException managerFailure) {
                    failure = appendFailure(failure, managerFailure);
                }
            }
            if (failure != null) throw failure;
        }
    }

    public static String scopeId(RunScope scope) {
        String identity = scope.workspaceId() + "\0" + scope.userId() + "\0" + scope.sessionId();
        return "thread:" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    @Override public boolean accepts(RunScope scope) { return workspaceId.equals(scope.workspaceId()); }

    @Override public synchronized void deleting(RunScope scope) {
        deleted.add(scope);
        BrowserRuntime runtime = runtimes.remove(scope);
        RuntimeException failure = null;
        try {
            if (runtime != null) runtime.close();
        } catch (RuntimeException runtimeFailure) {
            failure = runtimeFailure;
        } finally {
            try {
                clearCredentials.accept(scopeId(scope));
            } catch (RuntimeException credentialFailure) {
                failure = appendFailure(failure, credentialFailure);
            }
        }
        if (failure != null) throw failure;
    }

    @Override public synchronized void close() {
        closed = true;
        RuntimeException failure = null;
        try {
            for (BrowserRuntime runtime : runtimes.values()) {
                try {
                    runtime.close();
                } catch (RuntimeException runtimeFailure) {
                    failure = appendFailure(failure, runtimeFailure);
                }
            }
        } finally {
            try {
                for (RunScope scope : runtimes.keySet()) {
                    try {
                        clearCredentials.accept(scopeId(scope));
                    } catch (RuntimeException credentialFailure) {
                        failure = appendFailure(failure, credentialFailure);
                    }
                }
            } finally {
                runtimes.clear();
            }
        }
        if (failure != null) throw failure;
    }

    private static RuntimeException appendFailure(RuntimeException first, RuntimeException next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }
}
