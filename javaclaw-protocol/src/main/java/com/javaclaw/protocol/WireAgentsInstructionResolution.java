package com.javaclaw.protocol;

import java.util.List;

/**
 * 有效 AGENTS.md 链的只读协议投影。
 *
 * @param workingDirectory 本次解析工作目录
 * @param sources 按覆盖顺序排列的来源元数据
 * @param warnings 截断与环境状态警告
 * @param totalProjectBytes 实际项目层字节数
 */
public record WireAgentsInstructionResolution(
        String workingDirectory,
        List<WireAgentsInstructionSource> sources,
        List<String> warnings,
        long totalProjectBytes) {
    /** 固定来源和警告列表，避免客户端映射期间改变响应。 */
    public WireAgentsInstructionResolution {
        sources = List.copyOf(sources);
        warnings = List.copyOf(warnings);
    }
}
