package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.SQLException;

/** V011 在提交 Schema 摘要之前验证本地主密钥表的关键列；建表不生成密钥。 */
final class LocalVaultSchemaValidation {
    void validate(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var ignored =
                        statement.executeQuery("SELECT KEY_ID,KEY_BYTES FROM CORE.VAULT_LOCAL_MASTER_KEY WHERE 1=0")) {
            // 可重入 DDL 不修补已存在的残缺表；缺列时保留 pending，禁止记录建表完成。
        }
    }
}
