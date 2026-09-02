package com.javaclaw.server.security.grant;

/** 安全授权写命令的有界锁条带，防止同一幂等键在进程内并发提交。 */
final class GrantCommandLocks {
    private static final int STRIPES = 64;
    private static final Object[] LOCKS = createLocks();

    private GrantCommandLocks() {}

    static Object forKey(String key) {
        return LOCKS[Math.floorMod(key.hashCode(), STRIPES)];
    }

    private static Object[] createLocks() {
        Object[] locks = new Object[STRIPES];
        java.util.Arrays.setAll(locks, ignored -> new Object());
        return locks;
    }
}
