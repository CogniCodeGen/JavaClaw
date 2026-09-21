package com.javaclaw.application.agent;

import com.javaclaw.browser.PlaywrightBrowserManager;
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
    private final Map<RunScope, PlaywrightBrowserManager> runtimes = new HashMap<>();
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
        if (!accepts(scope)) throw new SecurityException("browser workspace mismatch");
        if (closed || deleted.contains(scope)) throw new IllegalStateException("thread browser runtime is closed");
        return runtimes.computeIfAbsent(scope, key -> factory.createIsolated(scopeId(key)));
    }

    public static String scopeId(RunScope scope) {
        String identity = scope.workspaceId() + "\0" + scope.userId() + "\0" + scope.sessionId();
        return "thread:" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    @Override public boolean accepts(RunScope scope) { return workspaceId.equals(scope.workspaceId()); }

    @Override public synchronized void deleting(RunScope scope) {
        deleted.add(scope);
        PlaywrightBrowserManager manager = runtimes.remove(scope);
        if (manager != null) manager.closePermanently();
        clearCredentials.accept(scopeId(scope));
    }

    @Override public synchronized void close() {
        closed = true;
        runtimes.values().forEach(PlaywrightBrowserManager::closePermanently);
        runtimes.keySet().forEach(scope -> clearCredentials.accept(scopeId(scope)));
        runtimes.clear();
    }
}
