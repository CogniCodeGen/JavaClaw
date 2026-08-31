package com.javaclaw.server.extension;

import java.nio.file.Path;

/** 插件来源签名验证边界；false 或异常均不能自动信任来源。 */
@FunctionalInterface
public interface PluginSignatureVerifier {
    PluginSignatureVerifier REJECT_UNTRUSTED = (payload, bundleRoot, signature) -> false;

    /**
     * 验证规范 payload 与可信公钥签名；bundleRoot 仅供受控包校验，不允许提升运行时权限。
     *
     * @throws Exception 验签或信任材料读取失败
     */
    boolean verify(byte[] payload, Path bundleRoot, PluginSignature signature) throws Exception;
}
