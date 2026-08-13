package com.javaclaw.framework.spi;

public final class RunCancelledException extends RuntimeException {
    public RunCancelledException() {
        super("run cancelled", null, false, false);
    }
}
