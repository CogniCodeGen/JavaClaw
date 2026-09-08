package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** 验证对话完成索引与取消意图的关键列，避免部分 DDL 仅凭已有表名被错误登记为完成。 */
final class ConversationSchemaValidation {
    void validate(Connection connection, int version) throws SQLException {
        List<String> queries =
                switch (version) {
                    case 6 ->
                        List.of(
                                "SELECT ID FROM CORE.CONVERSATION_COMPLETION_INIT WHERE 1=0",
                                "SELECT WORKSPACE_ID,COMMITTED_SEQUENCE FROM CORE.CONVERSATION_COMPLETION_HEAD WHERE 1=0",
                                "SELECT TURN_ID FROM CORE.CONVERSATION_EVIDENCE_EXCLUSION WHERE 1=0",
                                "SELECT TURN_ID,WORKSPACE_ID,THREAD_ID,COMPLETION_SEQUENCE,LAST_ITEM_SEQUENCE,COMPLETED_AT"
                                        + " FROM CORE.CONVERSATION_COMPLETION WHERE 1=0");
                    case 7 -> List.of("SELECT JOB_ID,REQUESTED_AT FROM CORE.EXTENSION_JOB_CANCELLATION WHERE 1=0");
                    default -> List.of();
                };
        for (String query : queries) {
            try (var statement = connection.createStatement();
                    var ignored = statement.executeQuery(query)) {
                // 先检验部分迁移的可用形状，再由既有 runner 提交 checksum 与 history。
            }
        }
    }
}
