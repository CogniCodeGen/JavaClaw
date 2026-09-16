package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** V008 在提交 migration checksum 之前验证浏览器授权表的关键列。 */
final class BrowserGrantSchemaValidation {
    void validate(Connection connection) throws SQLException {
        for (String query : List.of(
                "SELECT DIGEST,WORKSPACE_ID,THREAD_ID,PAYLOAD,EXPIRES_AT,CONSUMED FROM CORE.BROWSER_ORIGIN_PREVIEW WHERE 1=0",
                "SELECT ID,REVISION,STATE,WORKSPACE_ID,THREAD_ID,ORIGIN,PAYLOAD,CREATED_AT FROM CORE.BROWSER_ORIGIN_GRANT WHERE 1=0",
                "SELECT TURN_ID,SNAPSHOT_ID,WORKSPACE_ID,THREAD_ID,PAYLOAD FROM CORE.BROWSER_TURN_GRANT_SNAPSHOT WHERE 1=0",
                "SELECT ID,WORKSPACE_ID,THREAD_ID,PAYLOAD,DECIDED_AT FROM CORE.BROWSER_GRANT_DECISION WHERE 1=0")) {
            try (var statement = connection.createStatement();
                    var ignored = statement.executeQuery(query)) {
                // 已存在但缺列的半完成迁移不能被记录为成功。
            }
        }
    }
}
