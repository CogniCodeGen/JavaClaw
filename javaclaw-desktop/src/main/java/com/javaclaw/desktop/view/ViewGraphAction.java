package com.javaclaw.desktop.view;

import java.util.Objects;
import java.util.Optional;

/**
 * 平台图谱的页面局部浏览意图；不携带 operation、脚本或业务命令。
 *
 * @param graphId 当前 schema 内的图标识
 * @param filter 筛选时的新条件；展开时为空
 * @param nodeId 展开时已选择的节点；筛选时为空字符串
 */
public record ViewGraphAction(String graphId, Optional<Filter> filter, String nodeId) {
    /** 校验浏览意图，避免同时筛选与展开。 */
    public ViewGraphAction {
        Objects.requireNonNull(graphId, "graphId");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(nodeId, "nodeId");
        if (filter.isPresent() == !nodeId.isEmpty()) {
            throw new IllegalArgumentException("图谱动作必须是筛选或节点展开");
        }
    }

    /**
     * @param query 最多 200 个字符的文本筛选
     * @param includeInactive 是否包含历史节点
     */
    public record Filter(String query, boolean includeInactive) {
        /** 统一去除首尾空白并限制查询长度。 */
        public Filter {
            query = Objects.requireNonNull(query, "query").strip();
            if (query.length() > 200) {
                throw new IllegalArgumentException("筛选最多 200 个字符");
            }
        }
    }
}
