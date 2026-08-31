package com.javaclaw.server.diagnostics;

import java.time.Instant;
import java.util.List;

/** Sanitized operational diagnostics authority. */
public interface DiagnosticsRepository {
    /** 读取最多 limit 条诊断，避免导出或 UI 查询形成无界结果。 */
    List<Record> list(int limit);

    /** 保存脱敏诊断并返回记录；调用方不得传入完整环境或凭据明文。 */
    Record append(String severity, String component, String code, String message, String detailsJson);

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
    record Record(
            String id,
            String severity,
            String component,
            String code,
            String message,
            String detailsJson,
            Instant createdAt) {}
}
