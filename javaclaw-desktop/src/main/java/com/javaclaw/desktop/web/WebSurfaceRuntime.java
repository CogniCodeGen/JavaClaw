package com.javaclaw.desktop.web;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Desktop 进程拥有的 WebKit 临时 profile；不属于业务 data-v6，不持久化正文或共享其他 Java 进程的目录。
 *
 * <p>同 JVM 的 WebEngine 支持共享目录锁，跨 JVM 不能共享。目录由 OS 临时根创建且包含 PID 与随机部分； 只删除本 owner 实际创建的路径，不扫描前缀、不跟随符号链接。强制终止或 Windows
 * 延迟释放锁的残留由 OS 临时目录回收。
 */
public final class WebSurfaceRuntime {
    private static Path directory;
    private static boolean shutdownRegistered;

    private WebSurfaceRuntime() {}

    static synchronized Path directory() throws IOException {
        if (directory == null) {
            directory = Files.createTempDirectory(
                    "javaclaw-webview-" + ProcessHandle.current().pid() + "-");
            if (!shutdownRegistered) {
                Runtime.getRuntime().addShutdownHook(new Thread(WebSurfaceRuntime::close, "javaclaw-webview-cleanup"));
                shutdownRegistered = true;
            }
        }
        return directory;
    }

    /**
     * 全部页面关闭后尽力删除本进程的临时 profile；也由 JVM shutdown hook 兜底。
     *
     * <p>清理失败不改为访问共享 HOME 目录，不删除其他进程的数据。调用者不得在仍活跃的 WebView 间调用此方法。
     */
    public static void close() {
        Path owned;
        synchronized (WebSurfaceRuntime.class) {
            owned = directory;
            directory = null;
        }
        if (owned == null) {
            return;
        }
        try {
            Files.walkFileTree(owned, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path path, IOException failure) throws IOException {
                    Files.deleteIfExists(path);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // WebKit 的释放可晚于 Java 节点拆卸；不强制解锁，残留仍仅位于本 owner 的 OS 临时目录。
        }
    }
}
