package com.javaclaw.protocol;

/** 5.0 允许的本地 Transport。 */
public enum TransportKind {
    /** 标准输入输出。 */
    STDIO,
    /** Unix Domain Socket。 */
    UDS,
    /** Windows Named Pipe，由 native host 提供。 */
    NAMED_PIPE
}
