package com.javaclaw.api;

import java.util.List;
import java.util.Objects;

/**
 * 服务端权威计算的逐层有效权限预览。
 *
 * @param effective 最终权限交集
 * @param layers 固定五层、按实际求交顺序排列
 * @param denialReasons 带层名称的汇总拒绝原因
 */
public record EffectivePermissionPreview(
        PermissionProfile effective, List<PermissionLayerResult> layers, List<String> denialReasons) {
    /** 复制结果并要求五个治理层顺序完整。 */
    public EffectivePermissionPreview {
        Objects.requireNonNull(effective, "effective");
        layers = List.copyOf(layers);
        denialReasons = List.copyOf(denialReasons);
        List<PermissionLayerKind> actual =
                layers.stream().map(PermissionLayerResult::layer).toList();
        if (!actual.equals(List.of(PermissionLayerKind.values()))) {
            throw new IllegalArgumentException("permission preview must contain all layers in order");
        }
        if (!layers.getLast().effectiveAfterLayer().equals(effective)) {
            throw new IllegalArgumentException("final layer and effective permission are inconsistent");
        }
    }
}
