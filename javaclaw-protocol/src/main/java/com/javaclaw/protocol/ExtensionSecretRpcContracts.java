package com.javaclaw.protocol;

import java.util.Objects;

/** 内置扩展秘密命令的通用密封入口；明文从不进入普通扩展 payload。 */
public final class ExtensionSecretRpcContracts {
    /** 单一平台密封命令路由。 */
    public static final String METHOD = "extension/secret/command";

    private ExtensionSecretRpcContracts() {}

    /**
     * 将普通领域元数据与会话密文绑定的命令。
     *
     * @param call 精确扩展地址和非敏感领域元数据
     * @param secret SDK 在序列化前密封的秘密
     */
    public record Payload(ExtensionRpcContracts.CallPayload call, SealedSecret secret) {
        /** 密封管理命令不允许冒充模型 Turn。 */
        public Payload {
            Objects.requireNonNull(call, "call");
            Objects.requireNonNull(secret, "secret");
            if (call.turnId().isPresent() || call.threadId().isPresent()) {
                throw new IllegalArgumentException("密封管理命令不能携带 Turn 或 Thread");
            }
        }
    }

    /**
     * 将密文用途绑定到扩展、Workspace、操作、目标资源及精确版本。
     *
     * @param call 非敏感调用身份
     * @param expectedRevision 领域对象的期望版本
     * @return 不暴露参数的固定长度用途标识
     */
    public static String purpose(ExtensionRpcContracts.CallPayload call, long expectedRevision) {
        return METHOD
                + '/'
                + new CanonicalJson()
                        .encode(new Binding(call, expectedRevision))
                        .sha256();
    }

    private record Binding(ExtensionRpcContracts.CallPayload call, long expectedRevision) {}
}
