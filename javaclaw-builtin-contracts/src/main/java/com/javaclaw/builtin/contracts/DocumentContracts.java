package com.javaclaw.builtin.contracts;

import java.util.List;

import com.javaclaw.api.CanonicalPayload;

/** 内置扩展文档读写共用的分页与删除契约。 */
public final class DocumentContracts {
    private DocumentContracts() {}

    /**
     * 单文档查询或删除参数。
     *
     * @param id 文档标识
     */
    public record Key(String id) {
        /** 校验标识。 */
        public Key {
            id = ContractValidation.text(id, "id");
        }
    }

    /**
     * 稳定键分页请求。
     *
     * @param afterKey 排他游标；从头为空字符串
     * @param limit 页大小，1 到 500
     */
    public record PageRequest(String afterKey, int limit) {
        /** 校验页大小。 */
        public PageRequest {
            afterKey = afterKey == null ? "" : afterKey;
            if (limit < 1 || limit > 500) {
                throw new IllegalArgumentException("limit must be between 1 and 500");
            }
        }
    }

    /**
     * 不复制领域 DTO 的规范文档页；typed facade 依据扩展契约解码每项。
     *
     * @param documents 规范领域文档
     * @param nextKey 下一页游标；空页沿用请求游标
     */
    public record Page(List<CanonicalPayload> documents, String nextKey) {
        /** 复制结果。 */
        public Page {
            documents = List.copyOf(documents);
            nextKey = nextKey == null ? "" : nextKey;
        }
    }

    /**
     * 删除结果。
     *
     * @param id 已删除文档
     */
    public record Deleted(String id) {
        /** 校验标识。 */
        public Deleted {
            id = ContractValidation.text(id, "id");
        }
    }
}
