package com.javaclaw.extension.spi;

import java.util.Optional;

import com.javaclaw.api.InputRequest;
import com.javaclaw.api.InputRequestRecord;

/** 扩展暂停 Turn 并创建受治理用户输入请求的唯一平台端口。 */
public interface InputRequestPort {
    /**
     * 原子创建输入请求、Core Item，并将运行中 Turn 切换为等待状态。
     *
     * <p>该端口不接受 Secret。凭据、密码和 Browser state 必须使用专用 Vault 平台动作。
     *
     * @param request 结构化输入请求
     * @return 持久化等待快照
     */
    InputRequestRecord open(InputRequest request);

    /**
     * 查询一个输入请求。
     *
     * @param requestId 请求 ID
     * @return 当前快照
     */
    Optional<InputRequestRecord> find(String requestId);

    /**
     * 关闭由 Workflow 专用交互 Turn 承载且已经决议的请求。
     *
     * <p>普通 Harness 输入不得调用该入口；实现必须核对 producer、请求终态与 Turn 状态，并保证重复调用安全。
     *
     * @param requestId 请求 ID
     * @param producerId 预期扩展 producer
     * @return 已关闭的请求快照
     */
    InputRequestRecord completeResolved(String requestId, String producerId);
}
