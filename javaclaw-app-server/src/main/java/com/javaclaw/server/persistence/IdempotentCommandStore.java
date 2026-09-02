package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;

/** 跨用例共享的持久幂等结果访问器；响应必须与领域写入处于同一 H2 事务。 */
public final class IdempotentCommandStore {
    private final IdempotencyRepository repository = new IdempotencyRepository();

    /** 创建无状态访问器。 */
    public IdempotentCommandStore() {}

    /**
     * 查找并校验同一幂等身份的既有响应。
     *
     * @param connection 当前事务连接
     * @param identity 完整命令身份
     * @return 已提交的规范响应
     * @throws SQLException 查询失败
     */
    public Optional<CanonicalPayload> recover(Connection connection, CommandIdentity identity) throws SQLException {
        CommandIdentity checked = Objects.requireNonNull(identity, "identity");
        return repository.find(connection, checked.idempotencyKey()).map(stored -> {
            if (!stored.method().equals(checked.method())
                    || !stored.requestDigest().equals(checked.requestDigest())) {
                throw PersistenceException.idempotencyConflict("幂等键已被不同命令使用");
            }
            return stored.response();
        });
    }

    /**
     * 在领域写入成功后记录规范响应。
     *
     * @param connection 当前事务连接
     * @param identity 完整命令身份
     * @param response 不含 Secret 的响应
     * @param createdAt 提交时间
     * @throws SQLException 写入失败
     */
    public void record(Connection connection, CommandIdentity identity, CanonicalPayload response, Instant createdAt)
            throws SQLException {
        repository.insert(
                connection,
                Objects.requireNonNull(identity, "identity"),
                Objects.requireNonNull(response, "response"),
                Objects.requireNonNull(createdAt, "createdAt"));
    }
}
