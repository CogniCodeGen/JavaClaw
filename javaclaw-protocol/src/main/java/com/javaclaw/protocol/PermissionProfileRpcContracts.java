package com.javaclaw.protocol;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.PermissionProfile;
import com.javaclaw.api.PermissionProfileRef;
import com.javaclaw.api.WorkspaceId;

/** PermissionProfile 管理与有效权限预览的 Protocol v2 DTO。 */
public final class PermissionProfileRpcContracts {
    private PermissionProfileRpcContracts() {}

    /**
     * 读取精确不可变版本。
     *
     * @param reference 配置引用
     */
    public record ReadPayload(PermissionProfileRef reference) {
        /** 校验引用。 */
        public ReadPayload {
            Objects.requireNonNull(reference, "reference");
        }
    }

    /**
     * 读取一个配置的全部历史。
     *
     * @param id 配置标识
     */
    public record HistoryPayload(String id) {
        /** 校验标识。 */
        public HistoryPayload {
            id = identifier(id, "id");
        }
    }

    /**
     * 从只读模板或既有版本克隆用户配置。
     *
     * @param source 精确源版本
     * @param newId 新用户配置标识
     */
    public record ClonePayload(PermissionProfileRef source, String newId) {
        /** 校验源与新标识。 */
        public ClonePayload {
            Objects.requireNonNull(source, "source");
            newId = identifier(newId, "newId");
        }
    }

    /**
     * 写入用户配置的新版本。
     *
     * @param profile 完整不可变版本
     */
    public record UpdatePayload(PermissionProfile profile) {
        /** 校验配置。 */
        public UpdatePayload {
            Objects.requireNonNull(profile, "profile");
        }
    }

    /**
     * 比较同一配置的两个 revision。
     *
     * @param id 配置标识
     * @param beforeVersion 基准版本
     * @param afterVersion 比较版本
     */
    public record DiffPayload(String id, long beforeVersion, long afterVersion) {
        /** 校验标识和版本。 */
        public DiffPayload {
            id = identifier(id, "id");
            requirePositive(beforeVersion, "beforeVersion");
            requirePositive(afterVersion, "afterVersion");
        }
    }

    /**
     * 有效权限逐层预览请求。
     *
     * @param workspaceId Workspace
     * @param profile 冻结 Profile 引用
     * @param turnGrant 可选 Turn grant
     * @param toolDeclaration 可选工具声明
     */
    public record EffectivePreviewPayload(
            WorkspaceId workspaceId,
            PermissionProfileRef profile,
            Optional<PermissionProfile> turnGrant,
            Optional<PermissionProfile> toolDeclaration) {
        /** 复制可选层并校验引用。 */
        public EffectivePreviewPayload {
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(profile, "profile");
            turnGrant = Objects.requireNonNull(turnGrant, "turnGrant");
            toolDeclaration = Objects.requireNonNull(toolDeclaration, "toolDeclaration");
        }
    }

    /**
     * 每个配置的最新版本列表。
     *
     * @param profiles 按标识排序的配置
     */
    public record ListResult(List<PermissionProfile> profiles) {
        /** 复制列表。 */
        public ListResult {
            profiles = List.copyOf(profiles);
        }
    }

    /**
     * 单个配置的不可变历史。
     *
     * @param profiles revision 升序历史
     */
    public record HistoryResult(List<PermissionProfile> profiles) {
        /** 复制列表。 */
        public HistoryResult {
            profiles = List.copyOf(profiles);
        }
    }

    private static String identifier(String value, String name) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (!checked.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return checked;
    }

    private static void requirePositive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
