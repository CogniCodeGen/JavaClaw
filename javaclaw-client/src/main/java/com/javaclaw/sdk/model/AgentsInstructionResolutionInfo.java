package com.javaclaw.sdk.model;

import java.nio.file.Path;
import java.util.List;

/**
 * 当前有效 AGENTS.md 链；不包含文件正文，也不提供写入接口。
 *
 * @param workingDirectory 本次解析工作目录
 * @param sources 按覆盖顺序排列的来源
 * @param warnings 截断或环境警告
 * @param totalProjectBytes 实际项目层字节数
 */
public record AgentsInstructionResolutionInfo(
        Path workingDirectory,
        List<AgentsInstructionSourceInfo> sources,
        List<String> warnings,
        long totalProjectBytes) {
    /** 固定来源和警告列表，供 Desktop 安全复用。 */
    public AgentsInstructionResolutionInfo {
        sources = List.copyOf(sources);
        warnings = List.copyOf(warnings);
    }
}
