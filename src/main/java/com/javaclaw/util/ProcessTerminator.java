package com.javaclaw.util;

import java.util.Comparator;
import java.util.List;

/** 外部进程树清理工具，避免只杀父 shell 后遗留编译器、测试或 sleep 子进程。 */
public final class ProcessTerminator {

    private ProcessTerminator() {
    }

    /** 强制终止进程及调用时仍存活的全部后代；清理失败按尽力语义忽略。 */
    public static void destroyTreeForcibly(Process process) {
        if (process == null) return;
        List<ProcessHandle> descendants;
        try {
            descendants = process.descendants()
                    .sorted(Comparator.comparingInt(ProcessTerminator::depth).reversed())
                    .toList();
        } catch (RuntimeException ignored) {
            // 某些受限环境不允许枚举系统进程；仍至少终止直接进程。
            descendants = List.of();
        }
        for (ProcessHandle child : descendants) {
            try {
                if (child.isAlive()) child.destroyForcibly();
            } catch (RuntimeException ignored) {
                // 进程可能已退出或当前平台拒绝访问，继续清理其余节点。
            }
        }
        try {
            if (process.isAlive()) process.destroyForcibly();
        } catch (RuntimeException ignored) {
            // 尽力清理；调用方仍会按超时/中断返回。
        }
    }

    private static int depth(ProcessHandle handle) {
        try {
            int depth = 0;
            ProcessHandle current = handle;
            while (current.parent().isPresent() && depth < 64) {
                current = current.parent().orElseThrow();
                depth++;
            }
            return depth;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
