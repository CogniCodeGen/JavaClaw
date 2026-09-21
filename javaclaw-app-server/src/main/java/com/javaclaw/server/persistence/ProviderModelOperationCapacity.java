package com.javaclaw.server.persistence;

import java.util.HashMap;
import java.util.Map;

/**
 * 已保存目录发现与草稿预览共享的内存额度。
 *
 * <p>取得额度后直到操作从其注册表移除才归还；终态保留与取消均不能让另一入口绕过 64 个总操作和每 session 4 个的边界。
 */
final class ProviderModelOperationCapacity {
    private static final int MAXIMUM_OPERATIONS = 64;
    private static final int MAXIMUM_OPERATIONS_PER_SESSION = 4;
    private final Map<String, Integer> sessions = new HashMap<>();
    private int total;

    synchronized void acquire(String owner) {
        if (total >= MAXIMUM_OPERATIONS) {
            throw PersistenceException.invalidRequest("Provider model discovery capacity is exhausted");
        }
        int count = sessions.getOrDefault(owner, 0);
        if (count >= MAXIMUM_OPERATIONS_PER_SESSION) {
            throw PersistenceException.invalidRequest("Provider model discovery session capacity is exhausted");
        }
        sessions.put(owner, count + 1);
        total++;
    }

    synchronized void release(String owner) {
        int count = sessions.getOrDefault(owner, 0);
        if (count == 0) {
            throw new IllegalStateException("Provider model operation capacity was already released");
        }
        if (count == 1) {
            sessions.remove(owner);
        } else {
            sessions.put(owner, count - 1);
        }
        total--;
    }
}
