package com.javaclaw.nativehost.sandbox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 有界终止 Sandbox wrapper 与其当前可见的完整后代树。 */
final class SandboxProcessTerminator {
    private static final Duration GRACE = Duration.ofMillis(250);

    private SandboxProcessTerminator() {}

    static void terminate(Process process) {
        List<ProcessHandle> descendants = new ArrayList<>(process.descendants().toList());
        java.util.Collections.reverse(descendants);
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        if (await(process)) {
            return;
        }
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        await(process);
    }

    private static boolean await(Process process) {
        try {
            return process.waitFor(GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
