package com.javaclaw.server.security.vault;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 线性化 Vault 变化与 Provider 运行时 lease 获取的窄门闩。
 *
 * <p>状态使用单调 epoch 区分每次关闭与重新开放。调用方获取运行时 lease 后必须再次校验原 epoch，避免一次快速的关闭/开放形成 ABA 窗口。
 */
public final class VaultRuntimeGate {
    private final AtomicLong state = new AtomicLong();

    /** 创建初始开放的运行时门闩。 */
    public VaultRuntimeGate() {}

    /**
     * 获取当前开放 epoch。
     *
     * @return 可用于 lease 获取后复核的偶数 epoch
     * @throws IllegalStateException Vault 变化尚未安全同步到运行时
     */
    public long requireOpenStamp() {
        long observed = state.get();
        if ((observed & 1L) != 0L) {
            throw new IllegalStateException("Vault 运行时尚未安全就绪，Provider 暂不可用");
        }
        return observed;
    }

    /**
     * 判断 lease 获取期间是否始终处于同一次开放 epoch。
     *
     * @param stamp 获取 lease 前读到的开放 epoch
     * @return epoch 未发生任何关闭或重开时为 true
     */
    public boolean remainsOpen(long stamp) {
        return (stamp & 1L) == 0L && state.get() == stamp;
    }

    ChangeTicket beginChange() {
        while (true) {
            long current = state.get();
            long blocked = current + ((current & 1L) == 0L ? 1L : 2L);
            if (state.compareAndSet(current, blocked)) {
                return new ChangeTicket(blocked, (current & 1L) == 0L);
            }
        }
    }

    void completeChange(ChangeTicket ticket) {
        state.compareAndSet(ticket.state(), ticket.state() + 1L);
    }

    void cancelChange(ChangeTicket ticket) {
        if (ticket.openBeforeChange()) {
            completeChange(ticket);
        }
    }

    record ChangeTicket(long state, boolean openBeforeChange) {}
}
