package com.javaclaw.server.coding;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** 有序完成所有清理并聚合异常；一个失败不得跳过其他执行或后续租约释放。 */
final class CodingCleanup {
    private CodingCleanup() {}

    static Exception close(Exception failure, AutoCloseable... resources) {
        Exception result = failure;
        for (AutoCloseable resource : resources) {
            if (resource != null) {
                try {
                    resource.close();
                } catch (Exception problem) {
                    result = append(result, problem);
                }
            }
        }
        return result;
    }

    static Exception append(Exception current, Exception next) {
        if (current == null) {
            return next;
        }
        if (current != next) {
            current.addSuppressed(next);
        }
        return current;
    }

    static boolean cleanCancellation(Exception failure) {
        return failure instanceof com.javaclaw.api.TurnCancelledException && failure.getSuppressed().length == 0;
    }

    static void awaitAll(List<CompletableFuture<Void>> futures) throws Exception {
        Exception failure = null;
        boolean interrupted = Thread.interrupted();
        for (CompletableFuture<Void> future : futures) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            boolean observed = false;
            while (!observed) {
                try {
                    future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    observed = true;
                } catch (InterruptedException problem) {
                    interrupted = true;
                } catch (Exception problem) {
                    failure = append(failure, problem);
                    observed = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
            failure = append(failure, new InterruptedException("等待 Coding 资源终结时收到中断"));
        }
        if (failure != null) {
            throw failure;
        }
    }
}
