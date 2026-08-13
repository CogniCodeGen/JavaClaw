package com.javaclaw.framework.spi;

/** Kernel-owned scheduler for extension jobs; extensions never create executors or timers. */
@FunctionalInterface
public interface BackgroundJobScheduler {
    Registration schedule(String extensionId, BackgroundJob job);

    interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    static BackgroundJobScheduler disabled() {
        return (extensionId, job) -> () -> {};
    }
}
