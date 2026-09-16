package com.javaclaw.server.persistence;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.builtin.contracts.CodingSystemContracts.Catalog;

/**
 * 事务外发现的系统入口证据；继承时不重新读取当前 Workspace 配置。
 *
 * @param catalog 已冻结目录，包括不可用入口
 * @param expectedRegistryRevision 普通新 Turn 提交前须重验的版本，继承快照时为空
 */
public record CodingSystemSelection(Catalog catalog, Optional<Long> expectedRegistryRevision) {
    /** 固定目录及版本条件。 */
    public CodingSystemSelection {
        Objects.requireNonNull(catalog, "catalog");
        expectedRegistryRevision = Objects.requireNonNull(expectedRegistryRevision, "expectedRegistryRevision");
    }

    /** @return 保留全部入口事实且不以当前配置覆盖的继承选择 */
    public CodingSystemSelection inherited() {
        return new CodingSystemSelection(catalog, Optional.empty());
    }
}
