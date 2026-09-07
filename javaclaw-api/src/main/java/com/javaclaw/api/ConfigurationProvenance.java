package com.javaclaw.api;

import java.util.Objects;

/**
 * 某一已解析执行字段的来源，不包含 Prompt 正文、凭据或内部策略细节。
 *
 * @param field 稳定字段名
 * @param source 来源层
 * @param sourceId 来源稳定标识
 * @param revision 来源 revision；未版本化的当前显式选择使用 0
 */
public record ConfigurationProvenance(String field, ConfigurationSource source, String sourceId, long revision) {
    /** 校验来源描述。 */
    public ConfigurationProvenance {
        field = Preconditions.identifier(field, "field");
        Objects.requireNonNull(source, "source");
        sourceId = Preconditions.boundedText(sourceId, "sourceId", 1000);
        revision = Preconditions.nonNegative(revision, "revision");
    }
}
