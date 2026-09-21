package com.javaclaw.server.persistence;

import java.util.Optional;

import com.javaclaw.api.CredentialRef;
import com.javaclaw.model.CredentialMaterial;

/**
 * 模型目录预览读取凭据版本与临时材料的窄边界，不允许创建、轮换或删除凭据。
 *
 * <p>调用方先持有 Provider 串行锁；实现须在同一个 Vault 锁区间内核对凭据版本并取得材料，不能在此边界执行网络请求。
 */
@FunctionalInterface
public interface ProviderModelPreviewCredentialPort {
    /**
     * 原子核对凭据快照，并按需借出由调用方关闭清零的独立材料。
     *
     * @param reference 从已校验 Provider 精确版本解析的引用；未绑定时为空
     * @param expectedRevision 凭据期望版本；不存在时为 0
     * @param materialRequired 为 true 时必须返回材料；为 false 时只校验版本并返回空
     * @return 需要材料时的独立所有权副本；调用方负责关闭，任何失败不得返回部分材料
     */
    Optional<CredentialMaterial> read(
            Optional<CredentialRef> reference, long expectedRevision, boolean materialRequired);
}
