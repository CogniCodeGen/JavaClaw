package com.javaclaw.extension.spi;

/** Embedding Provider 可区分的检索用途。 */
public enum EmbeddingPurpose {
    /** 被索引的知识正文。 */
    DOCUMENT,
    /** 用户检索查询。 */
    QUERY
}
