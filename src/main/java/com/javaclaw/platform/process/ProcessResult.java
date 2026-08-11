package com.javaclaw.platform.process;

import java.time.Duration;

/** 外部进程的完整终态快照。 */
public record ProcessResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut,
        boolean outputTruncated,
        Duration duration) {

    public boolean succeeded() {
        return !timedOut && exitCode == 0;
    }
}
