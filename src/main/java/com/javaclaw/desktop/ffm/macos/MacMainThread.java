package com.javaclaw.desktop.ffm.macos;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Bounded, read-only AppKit queries on the actual Cocoa main queue. */
final class MacMainThread {
    private MacMainThread() { }

    static long query(MacNative api, LongSupplier operation) {
        if (api.bool(api.cls("NSThread"), "isMainThread")) return operation.getAsLong();
        Query query = new Query(api, operation);
        try (MacBlock block = query.block()) {
            api.call("dispatch_async", null, new MemoryLayout[]{ValueLayout.ADDRESS, ValueLayout.ADDRESS},
                    api.symbol("_dispatch_main_q"), block.pointer());
            try { return query.answer.get(300, TimeUnit.MILLISECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return 0; }
            catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException unavailable) { return 0; }
            finally { query.expired = true; }
        }
    }

    private static final class Query {
        private final MacNative api;
        private final LongSupplier operation;
        private final CompletableFuture<Long> answer = new CompletableFuture<>();
        private volatile boolean expired;
        private final long deadline = System.nanoTime() + 300_000_000L;

        Query(MacNative api, LongSupplier operation) { this.api = api; this.operation = operation; }

        MacBlock block() {
            try {
                var callback = MethodHandles.lookup().findVirtual(Query.class, "run",
                        MethodType.methodType(void.class, MemorySegment.class)).bindTo(this);
                return new MacBlock(api, callback, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot create AppKit main query", failure);
            }
        }

        @SuppressWarnings("unused")
        private void run(MemorySegment ignored) {
            try (var pool = api.pool()) {
                answer.complete(!expired && System.nanoTime() < deadline ? operation.getAsLong() : 0L);
            } catch (Throwable failed) { answer.completeExceptionally(failed); }
        }
    }
}
