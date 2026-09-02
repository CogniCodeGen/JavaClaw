package com.javaclaw.nativehost.sandbox;

/** 原生 Sandbox 目标进程的标准错误处理。 */
public enum SandboxErrorMode {
    /** 丢弃标准错误，适用于可能包含 Browser 凭据或文档内容的 Worker。 */
    DISCARD,
    /** 将标准错误合并到受输出上限约束的标准输出，只用于用户显式运行的受管代码。 */
    MERGE_WITH_OUTPUT
}
