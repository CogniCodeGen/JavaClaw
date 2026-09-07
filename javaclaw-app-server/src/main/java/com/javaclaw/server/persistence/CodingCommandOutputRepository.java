package com.javaclaw.server.persistence;

import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.protocol.CanonicalJson;

/** 只保存原始命令输出的内容寻址引用；文本展示不能改变字节游标或替代原始证据。 */
public final class CodingCommandOutputRepository {
    private final H2Database database;
    private final H2Transactions transactions;
    private final CanonicalJson json;

    /**
     * 创建输出证据仓库。
     *
     * @param database data-v6 数据库
     * @param json 规范 JSON
     */
    public CodingCommandOutputRepository(H2Database database, CanonicalJson json) {
        this.database = Objects.requireNonNull(database, "database");
        transactions = new H2Transactions(database);
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * 创建使用同一数据库的增量输出仓库；最终输出引用的既有契约保持独立。
     *
     * @param attachments 同一 data-v6 的内容寻址附件服务
     * @return 按 Workspace 与 Turn 绑定的流式输出仓库
     */
    public CodingCommandStreamRepository streaming(AttachmentService attachments) {
        return new CodingCommandStreamRepository(database, attachments, json);
    }

    /**
     * 在命令完成、最终工具记账之前保存真实输出引用，失败后调用进入结果未知。
     *
     * @param workspaceId 操作所属 Workspace
     * @param operationId 服务端调用身份
     * @param output 已落入同 Workspace 附件存储的输出
     */
    public void record(WorkspaceId workspaceId, String operationId, Output output) {
        execute(connection -> {
            try (var statement = connection.prepareStatement("""
                UPDATE CORE.CODING_OPERATION SET OUTPUT_JSON=?
                WHERE ID=? AND WORKSPACE_ID=? AND STATE='STARTED' AND OUTPUT_JSON IS NULL
                """)) {
                statement.setString(1, json.encode(output).json());
                statement.setString(2, operationId);
                statement.setString(3, workspaceId.toString());
                if (statement.executeUpdate() != 1) {
                    throw new SecurityException("命令输出身份、状态或写入次数不匹配");
                }
            }
            return null;
        });
    }

    /**
     * 读取指定 Workspace 的原始输出引用；上层还须重新检查 Thread 与 Turn 读取权限。
     *
     * @param workspaceId 当前已验证 Workspace
     * @param operationId 仅用于定位的操作标识
     * @return 已保留的原始输出证据
     */
    public Output read(WorkspaceId workspaceId, String operationId) {
        return find(workspaceId, operationId).orElseThrow(() -> new SecurityException("命令原始输出不存在或不属于当前 Workspace"));
    }

    /**
     * 查找已保留输出，支持旧版本在最终 ToolResult 提交之前中断的恢复读取。
     *
     * @param workspaceId 上层已经验证的 Workspace
     * @param operationId 上层已经验证 Thread/Turn 归属的操作
     * @return 尚未保存输出或不在此 Workspace 时为空
     */
    public Optional<Output> find(WorkspaceId workspaceId, String operationId) {
        return execute(connection -> {
            try (var statement = connection.prepareStatement("""
                SELECT OUTPUT_JSON FROM CORE.CODING_OPERATION WHERE ID=? AND WORKSPACE_ID=?
                """)) {
                statement.setString(1, operationId);
                statement.setString(2, workspaceId.toString());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || rows.getString(1) == null) {
                        return Optional.empty();
                    }
                    return Optional.of(json.decode(new CanonicalPayload(rows.getString(1)), Output.class));
                }
            }
        });
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("命令输出证据事务失败", failure);
        }
    }

    /**
     * 两个输出通道的原始证据；字符串为 SHA-256，长度单位为字节且可为零。
     *
     * @param stdoutDigest stdout 完整摘要
     * @param stdoutBytes stdout 保留字节数
     * @param stderrDigest stderr 完整摘要
     * @param stderrBytes stderr 保留字节数
     */
    public record Output(String stdoutDigest, long stdoutBytes, String stderrDigest, long stderrBytes) {
        /** 校验引用和计数，不接受超出工具输出上限的记录。 */
        public Output {
            if (!stdoutDigest.matches("[0-9a-f]{64}")
                    || !stderrDigest.matches("[0-9a-f]{64}")
                    || stdoutBytes < 0
                    || stderrBytes < 0
                    || stderrBytes > 1_048_576
                    || stdoutBytes > 1_048_576 - stderrBytes) {
                throw new IllegalArgumentException("命令输出证据无效");
            }
        }
    }
}
