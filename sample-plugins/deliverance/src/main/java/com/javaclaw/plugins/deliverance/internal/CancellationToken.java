package com.javaclaw.plugins.deliverance.internal;

import java.util.concurrent.CancellationException;

public interface CancellationToken {
    boolean isCancellationRequested();

    default void throwIfCancellationRequested() {
        if (isCancellationRequested()) throw new CancellationException("service request cancelled");
    }
}
