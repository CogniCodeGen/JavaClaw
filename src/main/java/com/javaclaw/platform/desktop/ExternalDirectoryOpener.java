package com.javaclaw.platform.desktop;

import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 在托管 I/O 虚拟线程上交给操作系统打开本地目录。
 *
 * <p>实例线程安全；提交成功不代表桌面最终打开成功。路径必须是调用时存在的目录，失败只
 * 记录日志，不回调可能已经关闭的页面。</p>
 */
public final class ExternalDirectoryOpener {

    private static final Logger log = LoggerFactory.getLogger(ExternalDirectoryOpener.class);
    private final ManagedTaskExecutor tasks;

    public ExternalDirectoryOpener(ManagedTaskExecutor tasks) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    public void open(Path directory) {
        Path target = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath().normalize();
        tasks.submit(TaskSpec.io("open-external-directory"), context -> {
            try {
                if (!Files.isDirectory(target)) {
                    throw new IllegalArgumentException("目录不存在：" + target);
                }
                if (!Desktop.isDesktopSupported()) {
                    throw new IllegalStateException("当前平台不支持 Desktop API");
                }
                Desktop.getDesktop().open(target.toFile());
            } catch (Exception failure) {
                log.warn("打开目录失败: {}", target, failure);
            }
            return null;
        });
    }
}
