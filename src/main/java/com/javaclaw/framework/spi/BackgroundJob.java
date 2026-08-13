package com.javaclaw.framework.spi;

import java.time.Duration;

/** Extension maintenance work. Implementations run on the kernel executor and must be cancellable. */
public interface BackgroundJob {
    String id();

    Duration interval();

    void run(CancellationToken cancellation) throws Exception;
}
