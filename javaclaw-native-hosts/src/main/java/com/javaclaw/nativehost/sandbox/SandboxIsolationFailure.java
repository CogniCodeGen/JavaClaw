package com.javaclaw.nativehost.sandbox;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import com.javaclaw.nativehost.ffm.WindowsSandbox;

/** 识别可信原生异常链中的隔离恢复失败；不检查用户输出文本或进程退出码。 */
public final class SandboxIsolationFailure {
    private SandboxIsolationFailure() {}

    /**
     * 有界扫描 cause 和 suppressed；同一个异常只访问一次以避免清理环路。
     *
     * @param failure 原生执行或聚合清理异常
     * @return 确认恢复异常时返回可信目录集合；目录可为空，表示没有足够凭据自动恢复
     */
    public static Optional<List<Path>> evidence(Throwable failure) {
        if (failure == null) {
            return Optional.empty();
        }
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var pending = new ArrayDeque<Throwable>();
        var paths = new LinkedHashSet<Path>();
        pending.add(failure);
        boolean found = false;
        while (!pending.isEmpty() && visited.size() < 256) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof WindowsSandbox.AclRestorationException restoration) {
                found = true;
                restoration.evidenceDirectory().ifPresent(paths::add);
            }
            if (current.getCause() != null) {
                pending.addLast(current.getCause());
            }
            Collections.addAll(pending, current.getSuppressed());
        }
        // 来自可信清理路径的异常图若超出检查预算，同样不能证明隔离已恢复。
        return found || !pending.isEmpty() ? Optional.of(List.copyOf(paths)) : Optional.empty();
    }
}
