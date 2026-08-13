package com.javaclaw.framework.spi;

import java.time.Duration;

/** Provider-neutral embedding boundary used by Memory and Knowledge extensions. */
public interface EmbeddingModelProvider {
    boolean configured();

    String initializationError();

    double[] embed(String text, Duration timeout) throws Exception;
}
