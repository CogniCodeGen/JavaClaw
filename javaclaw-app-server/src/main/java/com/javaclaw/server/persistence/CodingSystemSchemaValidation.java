package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/** V010 关键列验证；可重入 DDL 必须完整可用才能提交迁移 checksum。 */
final class CodingSystemSchemaValidation {
    void validate(Connection connection) throws SQLException {
        for (String query : List.of(
                "SELECT WORKSPACE_ID,REVISION,REGISTRY_JSON,UPDATED_AT FROM CORE.CODING_SYSTEM_REGISTRY WHERE 1=0",
                "SELECT TURN_ID,CATALOG_JSON,CATALOG_DIGEST,CREATED_AT FROM CORE.TURN_SYSTEM_ENVIRONMENT WHERE 1=0",
                "SELECT SNAPSHOT_ID,WORKSPACE_ID,CATALOG_JSON,CATALOG_DIGEST,CREATED_AT"
                        + " FROM CORE.CODING_EXECUTION_SYSTEM_ENVIRONMENT WHERE 1=0")) {
            try (var statement = connection.createStatement();
                    var ignored = statement.executeQuery(query)) {
                // 缺列立即阻止 SCHEMA_HISTORY 写入，不以表存在代替契约校验。
            }
        }
    }
}
