package com.javaclaw.server.diagnostics;

import java.time.Instant;
import java.util.List;

/**
 * Sanitized diagnostic projection. Details remain canonical JSON until protocol mapping.
 *
 * @param format 诊断格式标识
 * @param generatedAt 快照生成时间
 * @param javaVersion 运行 JDK 版本
 * @param osName 操作系统名称
 * @param osArch 处理器架构标识
 * @param availableProcessors JVM 可用处理器数量
 * @param credentialsIncluded 凭据是否包含；正常诊断必须为 false
 * @param environmentIncluded 完整环境变量是否包含；正常诊断必须为 false
 * @param records 非空诊断记录列表，构造时复制
 */
public record DiagnosticsSnapshot(
        String format,
        Instant generatedAt,
        String javaVersion,
        String osName,
        String osArch,
        int availableProcessors,
        boolean credentialsIncluded,
        boolean environmentIncluded,
        List<Record> records) {
    /** 复制诊断记录列表；调用方必须先完成敏感字段过滤。 */
    public DiagnosticsSnapshot {
        records = List.copyOf(records);
    }

    /**
     * 可导出的诊断条目，包含稳定代码、组件及脱敏详情。
     *
     * @param id 资源或声明的稳定标识
     * @param severity 诊断严重等级
     * @param component 产生诊断的组件标识
     * @param code 稳定错误或诊断代码
     * @param message 错误/诊断摘要；对外发送前必须脱敏
     * @param detailsJson 结构化诊断详情 JSON；不得包含环境变量或凭据明文
     * @param createdAt 创建时间
     */
    public record Record(
            String id,
            String severity,
            String component,
            String code,
            String message,
            String detailsJson,
            Instant createdAt) {}
}
