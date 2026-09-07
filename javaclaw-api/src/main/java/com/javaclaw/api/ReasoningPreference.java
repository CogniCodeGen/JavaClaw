package com.javaclaw.api;

/** 模型推理强度选择；由 Provider Adapter 校验支持范围。 */
public enum ReasoningPreference {
    /** 请求 NONE 推理强度。 */
    NONE,
    /** 请求 MINIMAL 推理强度。 */
    MINIMAL,
    /** 请求 LOW 推理强度。 */
    LOW,
    /** 请求 MEDIUM 推理强度。 */
    MEDIUM,
    /** 请求 HIGH 推理强度。 */
    HIGH,
    /** 请求 XHIGH 推理强度。 */
    XHIGH,
    /** 请求 MAX 推理强度。 */
    MAX
}
