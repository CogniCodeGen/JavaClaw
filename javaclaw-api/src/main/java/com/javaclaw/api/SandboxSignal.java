package com.javaclaw.api;

/** 可传给 Sandbox 会话的受控信号。 */
public enum SandboxSignal {
    /** 请求正常中断。 */
    INTERRUPT,
    /** 请求终止。 */
    TERMINATE,
    /** 超过宽限期后强制结束。 */
    KILL
}
