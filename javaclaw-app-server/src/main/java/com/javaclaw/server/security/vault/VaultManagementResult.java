package com.javaclaw.server.security.vault;

import com.javaclaw.api.VaultManagementReceipt;

/** 区分实际提交的 Vault 管理操作与幂等回放。 */
record VaultManagementResult(VaultManagementReceipt receipt, boolean changed) {}
