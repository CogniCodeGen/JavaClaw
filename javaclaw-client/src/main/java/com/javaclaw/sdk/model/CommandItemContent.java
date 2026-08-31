package com.javaclaw.sdk.model;

/**
 * 沙箱命令的退出码、输出与终止原因。
 *
 * @param argv 命令参数，不隐式经过 Shell
 * @param exitCode 子进程退出码
 * @param stdout 有界标准输出
 * @param stderr 有界错误输出
 * @param timedOut 是否超时
 * @param truncated 输出是否裁剪
 * @param document 完整原始 JSON，保留未知扩展
 */
public record CommandItemContent(
        java.util.List<String> argv,
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut,
        boolean truncated,
        JsonDocument document)
        implements ItemContent {
    /** 固定集合，防止界面修改事件快照。 */
    public CommandItemContent {
        argv = java.util.List.copyOf(argv);
    }

    @Override
    public String kind() {
        return "commandExecution";
    }
}
