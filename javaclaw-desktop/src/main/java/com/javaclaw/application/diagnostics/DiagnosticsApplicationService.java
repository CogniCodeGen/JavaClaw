package com.javaclaw.application.diagnostics;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 查询运行轨迹并导出脱敏诊断包的应用入口。
 *
 * <p>实现由根 Spring Context 管理且可并发调用。两个入口都执行阻塞文件 I/O，调用方
 * 必须提交到托管 I/O 执行器，不得在 JavaFX Application Thread 调用。查询是只读操作；
 * 导出可能创建或覆盖调用方指定的文件，失败时保留底层 {@link IOException}。
 * 取消依赖线程中断，页面关闭后还必须丢弃迟到结果。</p>
 */
public interface DiagnosticsApplicationService {

    List<String> query(Query query) throws IOException;

    ExportReceipt export(Path target) throws IOException;

    enum TimeRange {
        LAST_15_MINUTES(Duration.ofMinutes(15)),
        LAST_HOUR(Duration.ofHours(1)),
        LAST_24_HOURS(Duration.ofDays(1)),
        ALL(null);

        private final Duration lookback;

        TimeRange(Duration lookback) {
            this.lookback = lookback;
        }

        public Duration lookback() {
            return lookback;
        }
    }

    /** 不可变查询条件；空白文本表示不启用对应筛选。 */
    record Query(
            TimeRange range,
            String agent,
            String eventType,
            String keyword,
            int limit) {

        public Query {
            range = range == null ? TimeRange.ALL : range;
            agent = normalize(agent);
            eventType = normalize(eventType);
            keyword = normalize(keyword);
            if (limit < 1 || limit > 10_000) {
                throw new IllegalArgumentException("诊断查询条数必须在 1 到 10000 之间");
            }
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    /** 成功导出的绝对目标与实际字节数。 */
    record ExportReceipt(Path target, long bytes) {
        public ExportReceipt {
            if (target == null) throw new NullPointerException("target");
            target = target.toAbsolutePath().normalize();
            if (bytes < 0) throw new IllegalArgumentException("导出字节数不能为负数");
        }
    }
}
