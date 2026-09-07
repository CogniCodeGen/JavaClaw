package com.javaclaw.protocol;

import java.util.Objects;

/** Provider 模型目录发现临时操作的 Protocol v3 契约。 */
public final class ProviderModelDiscoveryRpcContracts {
    /** 用户或页面生命周期主动取消，不携带自由文本。 */
    public static final String CLIENT_CANCELLED = "CLIENT_CANCELLED";
    /** SDK 等待线程被中断，不携带自由文本。 */
    public static final String THREAD_INTERRUPTED = "THREAD_INTERRUPTED";
    /** 创建发现操作；expected revision 必须等于精确 Provider 版本。 */
    public static final String START_METHOD = "provider/model/discovery/start";
    /** 读取当前会话拥有的发现操作。 */
    public static final String READ_METHOD = "provider/model/discovery/read";
    /** 取消当前会话拥有的发现操作。 */
    public static final String CANCEL_METHOD = "provider/model/discovery/cancel";

    private ProviderModelDiscoveryRpcContracts() {}

    /**
     * 操作读取参数。
     *
     * @param operationId 服务端返回的临时操作标识
     */
    public record ReadPayload(String operationId) {
        /** 校验操作标识。 */
        public ReadPayload {
            operationId = identifier(operationId, "operationId");
        }
    }

    /**
     * 操作取消参数。
     *
     * @param operationId 服务端返回的临时操作标识
     * @param reason 不含敏感信息的取消原因
     */
    public record CancelPayload(String operationId, String reason) {
        /** 校验操作标识和原因。 */
        public CancelPayload {
            operationId = identifier(operationId, "operationId");
            reason = identifier(reason, "reason");
            if (!CLIENT_CANCELLED.equals(reason) && !THREAD_INTERRUPTED.equals(reason)) {
                throw new IllegalArgumentException("reason is not an allowed cancellation code");
            }
        }
    }

    private static String identifier(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty() || normalized.length() > 240) {
            throw new IllegalArgumentException(name + " length must be between 1 and 240");
        }
        return normalized;
    }
}
