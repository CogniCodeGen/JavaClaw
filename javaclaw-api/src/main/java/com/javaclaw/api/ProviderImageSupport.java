package com.javaclaw.api;

/** 精确 Provider 版本和模型的图片输入声明；未知状态不能被当作已支持。 */
public enum ProviderImageSupport {
    /** 尚未声明或验证，允许用户显式配置。 */
    UNKNOWN,
    /** 用户配置或明确验证该模型支持图片输入。 */
    SUPPORTED,
    /** 模型只接收文本，不发送图片字节。 */
    UNSUPPORTED
}
