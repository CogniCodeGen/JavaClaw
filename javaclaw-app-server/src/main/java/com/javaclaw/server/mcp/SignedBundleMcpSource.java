package com.javaclaw.server.mcp;

/** 已验签、实时可调用的第三方 Bundle MCP 启动来源。 */
public interface SignedBundleMcpSource {
    /**
     * 重新检查签名者信任、Bundle revision、启用状态、健康和退避，再返回启动快照。
     *
     * @param bundleId Bundle 扩展标识
     * @return 当前精确启动快照
     */
    SignedBundleMcpLaunch require(String bundleId);
}
