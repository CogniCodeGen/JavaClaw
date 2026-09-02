package com.javaclaw.client.extension;

import java.util.List;

/**
 * SDK 解码后的内置扩展文档页。
 *
 * @param documents 强类型文档
 * @param nextKey 下一页排他游标
 * @param <T> 领域文档类型
 */
public record TypedDocumentPage<T>(List<T> documents, String nextKey) {
    /** 复制结果。 */
    public TypedDocumentPage {
        documents = List.copyOf(documents);
        nextKey = nextKey == null ? "" : nextKey;
    }
}
