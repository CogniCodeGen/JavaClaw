package com.javaclaw.platform.desktop;

import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.net.URI;
import java.util.Objects;
import java.util.Set;

/**
 * 在托管 I/O 虚拟线程上交给操作系统打开外部链接。
 *
 * <p>实例线程安全；提交成功不代表系统浏览器最终打开成功。仅允许常见浏览协议，非法 URI
 * 在调用线程同步拒绝，系统调用失败只记录日志，不回调已销毁的页面。</p>
 */
public final class ExternalLinkOpener {

    private static final Logger log = LoggerFactory.getLogger(ExternalLinkOpener.class);
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https", "mailto");

    private final ManagedTaskExecutor tasks;

    public ExternalLinkOpener(ManagedTaskExecutor tasks) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
    }

    public void open(String value) {
        URI uri = URI.create(Objects.requireNonNull(value, "value"));
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw new IllegalArgumentException("不允许打开的链接协议: " + value);
        }
        tasks.submit(TaskSpec.io("open-external-link"), context -> {
            try {
                if (!Desktop.isDesktopSupported()) {
                    throw new IllegalStateException("当前平台不支持 Desktop API");
                }
                Desktop.getDesktop().browse(uri);
            } catch (Exception failure) {
                log.warn("打开链接失败: {}", uri, failure);
            }
            return null;
        });
    }
}
