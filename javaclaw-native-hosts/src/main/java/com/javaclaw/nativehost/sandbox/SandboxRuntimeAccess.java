package com.javaclaw.nativehost.sandbox;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 服务端根据已安装工具链清单产生的运行库、缓存与子进程访问范围。
 *
 * <p>本类型不是模型输入，也不授予项目目录权限。调用方必须先校验清单所有权与本次命令的审批； Native Host 只负责规范化真实路径并把范围落实到 OS Sandbox，不能从 PATH 或用户 HOME 推导额外目录。
 *
 * @param readRoots 非空值列表，托管运行库的绝对只读目录
 * @param writeRoots 非空值列表，本次执行独占或已获租约的托管缓存、临时目录
 * @param executableRoots 非空值列表，允许执行的工具链文件或目录，必须位于上述访问根内
 */
public record SandboxRuntimeAccess(List<Path> readRoots, List<Path> writeRoots, List<Path> executableRoots) {
    /** 复制清单并拒绝相对路径；文件存在性由执行前校验处理。 */
    public SandboxRuntimeAccess {
        readRoots = paths(readRoots);
        writeRoots = paths(writeRoots);
        executableRoots = paths(executableRoots);
    }

    /** 返回不增加运行库或缓存访问的兼容配置。 */
    public static SandboxRuntimeAccess empty() {
        return new SandboxRuntimeAccess(List.of(), List.of(), List.of());
    }

    private static List<Path> paths(List<Path> paths) {
        return List.copyOf(Objects.requireNonNull(paths, "paths")).stream()
                .map(path -> {
                    if (!path.isAbsolute()) {
                        throw new IllegalArgumentException("runtime access paths must be absolute");
                    }
                    return path.normalize();
                })
                .toList();
    }
}
