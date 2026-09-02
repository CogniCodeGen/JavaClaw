package com.javaclaw.protocol;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.javaclaw.extension.spi.ContributionKind;
import com.javaclaw.extension.spi.ExtensionAvailability;
import com.javaclaw.extension.spi.ExtensionState;

/** Protocol v2 内置扩展管理的强类型 wire 契约。 */
public final class BuiltinExtensionRpcContracts {
    private BuiltinExtensionRpcContracts() {}

    /** 内置能力的执行边界。 */
    public enum RuntimeKind {
        /** 随发行版进程内启动的可信 Bundle。 */
        BUNDLE,
        /** 由 App Server 平台 Host 提供、但受同一目录治理的能力。 */
        PLATFORM
    }

    /** 空查询参数。 */
    public record ListPayload() {}

    /**
     * 内置扩展资源标识。
     *
     * @param extensionId 稳定扩展标识
     */
    public record ExtensionPayload(String extensionId) {
        /** 规范化扩展标识。 */
        public ExtensionPayload {
            extensionId = text(extensionId, "extensionId");
        }
    }

    /**
     * 内置扩展当前管理状态。
     *
     * @param id 稳定扩展标识
     * @param displayName 展示名称
     * @param version 发行版版本
     * @param descriptorRevision 贡献内容冻结版本
     * @param stateRevision 启停状态乐观锁版本
     * @param availability 是否允许停用
     * @param state 当前实时状态
     * @param runtimeKind 执行边界
     * @param contributionKinds 贡献类别
     * @param updatedAt 最近状态更新时间
     */
    public record Status(
            String id,
            String displayName,
            String version,
            long descriptorRevision,
            long stateRevision,
            ExtensionAvailability availability,
            ExtensionState state,
            RuntimeKind runtimeKind,
            Set<ContributionKind> contributionKinds,
            Instant updatedAt) {
        /** 固定集合并校验状态版本。 */
        public Status {
            id = text(id, "id");
            displayName = text(displayName, "displayName");
            version = text(version, "version");
            if (descriptorRevision < 1 || stateRevision < 1) {
                throw new IllegalArgumentException("extension revisions must be positive");
            }
            Objects.requireNonNull(availability, "availability");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(runtimeKind, "runtimeKind");
            contributionKinds = Set.copyOf(contributionKinds);
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    /**
     * 列表结果。
     *
     * @param extensions 按扩展标识排序的状态
     */
    public record ListResult(List<Status> extensions) {
        /** 固定结果。 */
        public ListResult {
            extensions = List.copyOf(extensions);
        }
    }

    /**
     * 单项结果。
     *
     * @param extension 权威状态
     */
    public record StatusResult(Status extension) {
        /** 校验状态。 */
        public StatusResult {
            Objects.requireNonNull(extension, "extension");
        }
    }

    private static String text(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
