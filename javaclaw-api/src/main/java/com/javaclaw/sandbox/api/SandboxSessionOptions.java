package com.javaclaw.sandbox.api;

/**
 * Options for a long-lived sandbox process. PTY requests must never silently fall back to pipes.
 *
 * @param pseudoTerminal 是否申请 PTY；false 表示普通管道
 * @param columns PTY 列数，范围 20 到 1000；管道模式必须为 0
 * @param rows PTY 行数，范围 5 到 1000；管道模式必须为 0
 */
public record SandboxSessionOptions(boolean pseudoTerminal, int columns, int rows) {
    /** 校验 PTY 尺寸范围；管道模式拒绝非零尺寸，避免调用方误以为已分配终端。 */
    public SandboxSessionOptions {
        if (pseudoTerminal) {
            if (columns < 20 || columns > 1_000 || rows < 5 || rows > 1_000) {
                throw new IllegalArgumentException("PTY dimensions are invalid");
            }
        } else if (columns != 0 || rows != 0) {
            throw new IllegalArgumentException("pipe sessions cannot specify PTY dimensions");
        }
    }

    /** 返回无 PTY、尺寸为零的管道会话参数。 */
    public static SandboxSessionOptions pipes() {
        return new SandboxSessionOptions(false, 0, 0);
    }

    /** 创建指定字符尺寸的 PTY 参数；越界尺寸会在构造时拒绝。 */
    public static SandboxSessionOptions pty(int columns, int rows) {
        return new SandboxSessionOptions(true, columns, rows);
    }
}
