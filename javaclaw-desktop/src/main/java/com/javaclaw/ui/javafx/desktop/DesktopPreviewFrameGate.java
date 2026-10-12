package com.javaclaw.ui.javafx.desktop;

import java.util.concurrent.atomic.AtomicLong;

/** Invalidates work started before a pause and rejects frames captured before it. */
final class DesktopPreviewFrameGate {
    private final AtomicLong version = new AtomicLong();
    private final AtomicLong pauseAtMillis = new AtomicLong(Long.MIN_VALUE);

    long version() { return version.get(); }

    void pause(long atMillis) {
        pauseAtMillis.accumulateAndGet(atMillis, Math::max);
        version.incrementAndGet();
    }

    void invalidate() { version.incrementAndGet(); }

    boolean accepts(long capturedAtMillis, long startedVersion) {
        return startedVersion == version.get() && capturedAtMillis > pauseAtMillis.get();
    }
}
