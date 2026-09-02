package com.javaclaw.protocol;

/** RPC 方法的副作用类别。 */
public enum RpcMethodKind {
    /** 无副作用且可重试。 */
    QUERY,
    /** 有副作用，要求幂等键与 expected revision。 */
    COMMAND,
    /** 仅服务端向客户端推送。 */
    NOTIFICATION
}
