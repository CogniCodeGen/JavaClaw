package com.javaclaw.framework.extension;

import com.javaclaw.framework.spi.ExtensionContext;

import java.time.Clock;
import java.util.concurrent.CompletableFuture;

/** Shared minimal ExtensionManager factory for external-JAR acceptance tests. */
final class ExtensionManagerTestManager {
    private ExtensionManagerTestManager() { }

    static ExtensionManager create() {
        return new ExtensionManager(new ExtensionContext(
                Clock.systemUTC(), Runnable::run,
                request -> CompletableFuture.failedFuture(
                        new AssertionError("model task not expected"))));
    }
}
