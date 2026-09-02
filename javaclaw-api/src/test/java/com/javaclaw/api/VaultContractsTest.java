package com.javaclaw.api;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VaultContractsTest {
    private static final Instant NOW = Instant.parse("2026-09-01T08:00:00Z");

    @Test
    void 管理回执仅保留脱敏动作计数和时间() {
        VaultManagementReceipt receipt = new VaultManagementReceipt(VaultManagementAction.MASTER_KEY_ROTATED, 3, NOW);

        assertEquals(VaultManagementAction.MASTER_KEY_ROTATED, receipt.action());
        assertEquals(3, receipt.affectedCredentialCount());
        assertEquals(NOW, receipt.completedAt());
    }

    @Test
    void 管理回执拒绝缺失字段和负数计数() {
        assertThrows(NullPointerException.class, () -> new VaultManagementReceipt(null, 0, NOW));
        assertThrows(
                IllegalArgumentException.class,
                () -> new VaultManagementReceipt(VaultManagementAction.VAULT_RESET, -1, NOW));
        assertThrows(
                NullPointerException.class,
                () -> new VaultManagementReceipt(VaultManagementAction.VAULT_RESET, 0, null));
    }
}
