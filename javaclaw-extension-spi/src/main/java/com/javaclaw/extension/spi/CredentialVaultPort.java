package com.javaclaw.extension.spi;

import java.util.List;
import java.util.Optional;

import com.javaclaw.api.CredentialMetadata;
import com.javaclaw.api.CredentialRef;

/** 向可信内置扩展公开的 Vault 脱敏元数据端口；永远不返回 Secret。 */
public interface CredentialVaultPort {
    /**
     * 读取一个 opaque 引用的脱敏元数据。
     *
     * @param reference Vault 引用
     * @return 引用仍存在时的 revision 与更新时间
     */
    Optional<CredentialMetadata> metadata(CredentialRef reference);

    /**
     * 列出一个命名空间中的脱敏元数据。
     *
     * @param namespace 精确命名空间
     * @return 按 opaque ID 排序的不可变列表
     */
    List<CredentialMetadata> listMetadata(String namespace);
}
