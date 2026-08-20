package com.javaclaw.inference.api;

/** 同一推理服务插件进程的排队优先级；不会抢占已经运行的请求。 */
public enum InferenceRequestPriority {
    INTERNAL,
    EXTERNAL
}
