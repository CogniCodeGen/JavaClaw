package com.javaclaw.server.persistence;

/** 进程内共享的幂等键条带锁，避免不同 Core 用例并发抢占同一个全局幂等键。 */
public final class CommandLocks {
    private static final Object[] LOCKS = create();

    private CommandLocks() {}

    /**
     * 返回幂等键对应的稳定进程内锁。
     *
     * @param idempotencyKey 已校验幂等键
     * @return 仅用于同步块的锁对象
     */
    public static Object forKey(String idempotencyKey) {
        return LOCKS[Math.floorMod(idempotencyKey.hashCode(), LOCKS.length)];
    }

    private static Object[] create() {
        Object[] values = new Object[64];
        java.util.Arrays.setAll(values, ignored -> new Object());
        return values;
    }
}
