package com.javaclaw.api;

import java.util.List;
import java.util.Objects;

/**
 * 已完成哈希校验的只读 Rollout 快照。
 *
 * @param manifest 完整性清单
 * @param items 按 sequence 排序的 Item
 */
public record RolloutSnapshot(RolloutManifest manifest, List<ItemEnvelope> items) {
    /** 复制 Item，避免调用方改变回放视图。 */
    public RolloutSnapshot {
        Objects.requireNonNull(manifest, "manifest");
        items = List.copyOf(items);
    }
}
