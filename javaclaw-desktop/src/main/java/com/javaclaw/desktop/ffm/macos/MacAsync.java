package com.javaclaw.desktop.ffm.macos;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Two-argument Objective-C completions, including late result and native block ownership. */
final class MacAsync implements AutoCloseable {
    private final MacNative api;
    private final CompletableFuture<Result> result = new CompletableFuture<>();
    private final MacBlock block;
    private boolean callerDone;

    MacAsync(MacNative api) {
        this.api = api;
        try {
            var invoke = MethodHandles.lookup().findVirtual(MacAsync.class, "complete",
                    MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class,
                            MemorySegment.class)).bindTo(this);
            block = new MacBlock(api, invoke, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot create Objective-C completion", failure);
        }
    }

    MemorySegment pointer() { return block.pointer(); }
    boolean disposed() { return block.disposed(); }

    @SuppressWarnings("unused")
    private void complete(MemorySegment ignored, MemorySegment value, MemorySegment error) {
        // Never allow a Java exception to cross an upcall boundary into Objective-C.
        try (var pool = api.pool()) {
            String detail = MacNative.nil(error) ? "" : api.text(api.object(error, "localizedDescription"));
            Result completed = new Result(api, api.retain(value), detail);
            synchronized (this) {
                if (!result.complete(completed)) completed.close();
                if (callerDone) completed.close();
            }
        } catch (Throwable failure) { result.completeExceptionally(failure); }
    }

    Result await(Duration timeout) {
        try { return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for macOS", interrupted);
        } catch (java.util.concurrent.TimeoutException timeoutFailure) {
            throw new IllegalStateException("macOS operation timed out; completion may still arrive", timeoutFailure);
        } catch (java.util.concurrent.ExecutionException failed) {
            throw new IllegalStateException("macOS completion failed", failed.getCause());
        }
    }

    @Override public synchronized void close() {
        callerDone = true;
        result.thenAccept(Result::close);
        // Drop only our native retain. An asynchronous Apple API's copy keeps both
        // the heap block and its Java callback alive until native final disposal.
        block.close();
    }

    static final class Result implements AutoCloseable {
        private final MacNative api;
        private MemorySegment pointer;
        private final String error;
        Result(MacNative api, MemorySegment pointer, String error) {
            this.api = api; this.pointer = pointer; this.error = error;
        }
        MemorySegment pointer() { return pointer; }
        String error() { return error; }
        @Override public synchronized void close() { api.release(pointer); pointer = MemorySegment.NULL; }
    }
}
