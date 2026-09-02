package com.javaclaw.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 一层权限求交后的可解释结果。
 *
 * @param layer 治理层
 * @param source 可选持久 Profile 来源
 * @param applied 是否提供并应用了这一层
 * @param effectiveAfterLayer 应用后有效权限
 * @param denialReasons 本层收窄的通俗原因
 */
public record PermissionLayerResult(
        PermissionLayerKind layer,
        Optional<PermissionProfileRef> source,
        boolean applied,
        PermissionProfile effectiveAfterLayer,
        List<String> denialReasons) {
    /** 复制结果并校验来源与说明。 */
    public PermissionLayerResult {
        Objects.requireNonNull(layer, "layer");
        source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(effectiveAfterLayer, "effectiveAfterLayer");
        denialReasons = denialReasons.stream()
                .map(reason -> bounded(reason, "denialReason", 500))
                .toList();
        if (!applied && !denialReasons.isEmpty()) {
            throw new IllegalArgumentException("an unapplied layer cannot deny permissions");
        }
    }

    private static String bounded(String value, String name, int maximumLength) {
        String checked = Preconditions.text(value, name);
        if (checked.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return checked;
    }
}
