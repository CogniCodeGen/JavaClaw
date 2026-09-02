package com.javaclaw.builtin.extensions;

import com.javaclaw.builtin.contracts.VersionedExtensionDocument;
import com.javaclaw.extension.spi.ExtensionExecutionContext;
import com.javaclaw.extension.spi.ExtensionRequest;
import com.javaclaw.extension.spi.ExtensionResponse;
import com.javaclaw.extension.spi.ExtensionTransaction;

/** 托管文档命令的领域策略；只承载事务边界内外必须显式衔接的行为。 */
interface ManagedDocumentBehavior<T extends VersionedExtensionDocument> {
    /** 创建无额外领域行为的策略。 */
    static <T extends VersionedExtensionDocument> ManagedDocumentBehavior<T> none() {
        return new ManagedDocumentBehavior<>() {};
    }

    /** 在事务开启前验证领域不变量，不得产生副作用。 */
    default void validate(
            ManagedDocumentResource<T> documents, ExtensionRequest request, ExtensionExecutionContext context)
            throws Exception {}

    /** 在主文档同一事务中写入派生状态或 Outbox。 */
    default void afterMutation(
            ManagedDocumentResource<T> documents,
            ExtensionRequest request,
            ExtensionResponse response,
            ExtensionTransaction transaction) {}

    /** 在命令提交后同步外部投影；幂等重放时也会调用。 */
    default void afterCommit(
            ManagedDocumentResource<T> documents,
            ExtensionRequest request,
            ExtensionResponse response,
            ExtensionExecutionContext context)
            throws Exception {}

    /** 在删除主文档的同一事务中清理派生记录。 */
    default void deleteRelated(
            ManagedDocumentResource<T> documents,
            ExtensionRequest request,
            String documentId,
            ExtensionTransaction transaction) {}
}
