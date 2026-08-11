package com.javaclaw.application.diagnostics;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 诊断轨迹存储端口。
 *
 * <p>实现必须可被并发调用且不拥有页面生命周期。方法执行阻塞 I/O，并把读写失败原样
 * 交给应用层；查询应为只读操作，导出允许创建或覆盖指定文件。取消通过中断协作完成。</p>
 */
public interface DiagnosticsArchivePort {

    List<String> query(
            String keyword,
            String agent,
            String eventType,
            long sinceEpochMillis,
            int limit) throws IOException;

    long export(Path target) throws IOException;
}
