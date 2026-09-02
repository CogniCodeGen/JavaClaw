package com.javaclaw.launcher.tray;

import java.util.Objects;
import java.util.Optional;

/** 发行 supervisor 暴露给托盘的窄 App Server 控制端口。 */
public interface TrayServerControl {
    /**
     * 探测本地 transport。
     *
     * @return 可连接时为 true
     * @throws Exception 平台 transport 探测失败
     */
    boolean running() throws Exception;

    /**
     * 启动或复用 App Server。
     *
     * @throws Exception 启动失败
     */
    void start() throws Exception;

    /**
     * 通过 Protocol v2 请求协作式停止，禁止强制杀进程。
     *
     * @return lease 或其他客户端可能拒绝操作
     * @throws Exception 本地 RPC 或等待退出失败
     */
    ControlResult stop() throws Exception;

    /**
     * 先协作式停止再重新启动。
     *
     * @return 停止门禁可能拒绝操作
     * @throws Exception 本地 RPC、等待退出或重新启动失败
     */
    ControlResult restart() throws Exception;

    /**
     * @param accepted 是否完成或已安排操作
     * @param reason 拒绝时的通俗原因
     */
    record ControlResult(boolean accepted, Optional<String> reason) {
        /** 校验结果与原因一致。 */
        public ControlResult {
            reason = Objects.requireNonNull(reason, "reason").map(String::strip);
            if (reason.filter(String::isEmpty).isPresent() || accepted == reason.isPresent()) {
                throw new IllegalArgumentException("control result and reason disagree");
            }
        }

        /** @return 接受操作的结果 */
        public static ControlResult success() {
            return new ControlResult(true, Optional.empty());
        }

        /**
         * 创建拒绝结果。
         *
         * @param reason 通俗原因
         * @return 拒绝结果
         */
        public static ControlResult rejected(String reason) {
            return new ControlResult(false, Optional.of(Objects.requireNonNull(reason, "reason")));
        }
    }
}
