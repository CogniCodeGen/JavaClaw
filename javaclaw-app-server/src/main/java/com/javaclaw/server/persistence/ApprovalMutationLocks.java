package com.javaclaw.server.persistence;

import java.util.Arrays;
import java.util.Objects;

/** 同一审批的进程内条带锁，避免过期、客户端决议和撤权同时进入 H2 事务。 */
final class ApprovalMutationLocks {
    private static final Object[] LOCKS = create();

    private ApprovalMutationLocks() {}

    static Object forId(String approvalId) {
        String checkedId = Objects.requireNonNull(approvalId, "approvalId");
        return LOCKS[Math.floorMod(checkedId.hashCode(), LOCKS.length)];
    }

    private static Object[] create() {
        Object[] values = new Object[64];
        Arrays.setAll(values, ignored -> new Object());
        return values;
    }
}
