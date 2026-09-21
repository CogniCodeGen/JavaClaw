package com.javaclaw.browser.protocol;

import com.javaclaw.builtin.contracts.SiteRegistrationContracts;

/** 登记复用常驻私有 framing；公开元数据与仅宿主可读的秘密二进制严格分离。 */
public final class BrowserRegistrationProtocol {
    /** Worker 启动入口。 */
    public static final String OPEN = "registration";
    /** 固定完成命令；不得经模型工具触发。 */
    public static final String COMPLETE = "registrationComplete";
    /** 单个用户名密码组合上限。 */
    public static final int MAXIMUM_CREDENTIAL_BYTES = 65_536;

    private BrowserRegistrationProtocol() {}

    /**
     * @param status 用户确认的页面与授权状态
     * @param stateBytes 后续二进制中的 storage state 字节数
     * @param credentialBytes 紧随 state 的可选用户名 NUL 密码字节数
     */
    public record PrivateResult(SiteRegistrationContracts.WorkerStatus status, int stateBytes, int credentialBytes) {
        /** 拒绝不符合两个独立预算的私有载荷。 */
        public PrivateResult {
            java.util.Objects.requireNonNull(status, "status");
            if (stateBytes < 1
                    || stateBytes > BrowserWorkerProtocol.MAXIMUM_STATE_BYTES
                    || credentialBytes < 0
                    || credentialBytes > MAXIMUM_CREDENTIAL_BYTES) {
                throw new IllegalArgumentException("registration private frame exceeds limit");
            }
        }
    }
}
