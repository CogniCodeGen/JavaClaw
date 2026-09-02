package com.javaclaw.protocol;

import java.util.Objects;

/**
 * initialize 请求。
 *
 * @param appProtocolVersion 客户端协议版本
 * @param clientInfo 客户端信息
 * @param capabilities 能力声明
 */
public record InitializeParams(int appProtocolVersion, ClientInfo clientInfo, CapabilityAdvertisement capabilities) {
    /** 校验必填字段；版本兼容性由 negotiator 返回稳定协议错误。 */
    public InitializeParams {
        Objects.requireNonNull(clientInfo, "clientInfo");
        Objects.requireNonNull(capabilities, "capabilities");
    }
}
