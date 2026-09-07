package com.javaclaw.server.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/** 持久化 App Server 活动 lease，并在查询时清除过期记录。 */
public final class LifecycleLeaseRepository {
    private final H2Transactions transactions;
    private final Clock clock;

    /**
     * 创建仓储。
     *
     * @param database data-v6 数据库
     * @param clock 平台时钟
     */
    public LifecycleLeaseRepository(H2Database database, Clock clock) {
        transactions = new H2Transactions(Objects.requireNonNull(database, "database"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 创建一个有界 lease。
     *
     * @param owner 持有者，例如 Turn 或 Schedule 标识
     * @param kind 稳定 lease 类别
     * @param lifetime 失效时间；进程崩溃后由此回收
     * @return lease 标识
     */
    public UUID acquire(String owner, String kind, Duration lifetime) {
        String checkedOwner = text(owner, "owner");
        String checkedKind = text(kind, "kind");
        Duration checkedLifetime = lifetime(lifetime);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        transact(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO CORE.LIFECYCLE_LEASE (ID, OWNER, KIND, EXPIRES_AT, CREATED_AT)
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, id.toString());
                statement.setString(2, checkedOwner);
                statement.setString(3, checkedKind);
                statement.setObject(4, now.plus(checkedLifetime).atOffset(ZoneOffset.UTC));
                statement.setObject(5, now.atOffset(ZoneOffset.UTC));
                statement.executeUpdate();
            }
            return null;
        });
        return id;
    }

    /**
     * 续期仍存在的 lease。
     *
     * @param id lease 标识
     * @param lifetime 从当前时刻计算的新有效期
     * @return lease 仍存在时为 true
     */
    public boolean renew(UUID id, Duration lifetime) {
        Objects.requireNonNull(id, "id");
        Instant expiresAt = Instant.now(clock).plus(lifetime(lifetime));
        return transact(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE CORE.LIFECYCLE_LEASE SET EXPIRES_AT = ? WHERE ID = ?
                    """)) {
                statement.setObject(1, expiresAt.atOffset(ZoneOffset.UTC));
                statement.setString(2, id.toString());
                return statement.executeUpdate() == 1;
            }
        });
    }

    /**
     * 释放 lease；重复释放安全。
     *
     * @param id lease 标识
     */
    public void release(UUID id) {
        Objects.requireNonNull(id, "id");
        transact(connection -> {
            try (PreparedStatement statement =
                    connection.prepareStatement("DELETE FROM CORE.LIFECYCLE_LEASE WHERE ID = ?")) {
                statement.setString(1, id.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    /**
     * 删除已过期记录并返回当前活动数。
     *
     * @return 活动 lease 数量
     */
    public int activeCount() {
        Instant now = Instant.now(clock);
        return transact(connection -> {
            try (PreparedStatement delete =
                    connection.prepareStatement("DELETE FROM CORE.LIFECYCLE_LEASE WHERE EXPIRES_AT <= ?")) {
                delete.setObject(1, now.atOffset(ZoneOffset.UTC));
                delete.executeUpdate();
            }
            try (PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM CORE.LIFECYCLE_LEASE");
                    ResultSet result = count.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        });
    }

    private <T> T transact(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (Exception failure) {
            throw new PersistenceException("Lifecycle lease 事务失败", failure);
        }
    }

    private static Duration lifetime(Duration value) {
        Duration checked = Objects.requireNonNull(value, "lifetime");
        if (checked.isZero() || checked.isNegative()) {
            throw new IllegalArgumentException("lifetime must be positive");
        }
        return checked;
    }

    private static String text(String value, String name) {
        String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
