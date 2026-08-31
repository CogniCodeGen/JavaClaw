package com.javaclaw.server.diagnostics;

import com.javaclaw.core.api.AttachmentMetadata;

/** Sanitized diagnostics boundary consumed by protocol handlers. */
public interface DiagnosticsUseCases {
    /** 生成最多 limit 条记录的脱敏运行诊断；不包含凭据或完整环境变量。 */
    DiagnosticsSnapshot read(int limit);

    /**
     * 生成脱敏诊断附件并返回内容寻址 metadata；调用方通过附件接口下载，不访问服务端文件路径。
     *
     * @throws Exception 导出编码或附件保存失败
     */
    AttachmentMetadata export();
}
