package com.javaclaw.infrastructure.memory;

import com.javaclaw.framework.api.RunScope;
import com.javaclaw.framework.spi.ThreadLifecycleListener;
import com.javaclaw.memory.ThreadMemoryCleanup;
import java.nio.file.Path;

/** Always present in the root runtime; cold-workspace deletion must finish before H2 purge. */
public final class ThreadMemoryArtifactCleaner implements ThreadLifecycleListener {
    private final ThreadMemoryCleanup cleanup;
    public ThreadMemoryArtifactCleaner(Path globalDataRoot) { cleanup = new ThreadMemoryCleanup(globalDataRoot); }
    @Override public boolean accepts(RunScope scope) { return true; }
    @Override public void deleting(RunScope scope) { cleanup.delete(scope); }
}
