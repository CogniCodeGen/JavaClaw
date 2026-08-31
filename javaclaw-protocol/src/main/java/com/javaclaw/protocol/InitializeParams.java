package com.javaclaw.protocol;

import java.util.Map;

/**
 * JSON-RPC 初始化参数；版本与客户端身份在 Session 边界校验。
 *
 * @param protocolVersion 协商后的 JavaClaw 协议版本，不是 JSON-RPC 的 2.0 字段
 * @param client 客户端身份描述；初始化时必须提供有效 name/version
 * @param capabilities 能力开关快照；null 归一为空 Map
 */
public record InitializeParams(int protocolVersion, ClientInfo client, Map<String, Boolean> capabilities) {
    /** 固定能力声明 Map；不在 DTO 构造器中选择或降级协议版本。 */
    public InitializeParams {
        capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
    }

    /**
     * 用于握手和诊断的客户端身份描述，不作为授权依据。
     *
     * @param name 展示名称或资源名称；有效服务端响应中非空
     * @param title 展示标题
     * @param version 组件或声明的版本字符串
     */
    public record ClientInfo(String name, String title, String version) {}
}
