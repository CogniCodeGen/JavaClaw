package com.javaclaw.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Turn 冻结的 Provider 端点与模型引用。
 *
 * @param endpointId Provider 标识
 * @param endpointRevision 精确不可变版本
 * @param model Provider 原生模型名
 */
public record ProviderRef(String endpointId, long endpointRevision, String model) {
    /** 校验引用。 */
    public ProviderRef {
        endpointId = Preconditions.identifier(endpointId, "endpointId");
        endpointRevision = Preconditions.positive(endpointRevision, "endpointRevision");
        model = Preconditions.text(model, "model");
    }

    /**
     * 返回只在进程内传递给 ModelGateway 的稳定路由键。
     *
     * @return 不暴露模型名的路由键
     */
    public String routeKey() {
        String material = endpointId + "\n" + endpointRevision + "\n" + model;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return endpointId + "." + endpointRevision + "." + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
