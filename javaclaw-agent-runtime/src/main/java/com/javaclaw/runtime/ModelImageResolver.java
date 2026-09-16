package com.javaclaw.runtime;

/** 组合根提供的图片读取边界；每次读取须复核 Workspace、Thread 引用及内容摘要，不允许读取任意 URL。 */
@FunctionalInterface
public interface ModelImageResolver {
    /**
     * 读取已经授权的附件图片。
     *
     * @param image 可信模型投影
     * @return 调用者拥有的图片字节副本；不得记录到日志
     */
    byte[] resolve(ModelImage image);

    /**
     * 为单次真实模型请求绑定可信 Turn；生产宿主必须同时校验引用与该 Turn 的 Thread 所有权。
     *
     * @param turnId 实际发起模型或压缩请求的 Turn
     * @return 只在此次请求中使用的读取器；默认实现适用于没有宿主存储的测试端口
     */
    default ModelImageResolver forTurn(com.javaclaw.api.TurnId turnId) {
        java.util.Objects.requireNonNull(turnId, "turnId");
        return this;
    }

    /** @return 没有附件读取能力时的显式拒绝实现 */
    static ModelImageResolver unavailable() {
        return image -> {
            throw new IllegalStateException("当前模型端点未配置可信图片读取能力");
        };
    }
}
