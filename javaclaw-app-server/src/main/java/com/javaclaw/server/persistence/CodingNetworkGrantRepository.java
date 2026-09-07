package com.javaclaw.server.persistence;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.server.security.CommandNetworkGrant;

/** 短期网络授权账本；重启后只撤销，不从旧记录重建可用代理。 */
public final class CodingNetworkGrantRepository {
    private final H2Transactions transactions;
    private final CanonicalJson json;
    private final Clock clock;

    /**
     * 创建网络账本。
     *
     * @param database data-v6
     * @param json 规范 JSON
     * @param clock 平台时钟
     */
    public CodingNetworkGrantRepository(H2Database database, CanonicalJson json, Clock clock) {
        transactions = new H2Transactions(database);
        this.json = json;
        this.clock = clock;
        execute(connection -> {
            try (var update = connection.prepareStatement("""
                    UPDATE CORE.CODING_NETWORK_GRANT SET STATE='REVOKED',UPDATED_AT=CURRENT_TIMESTAMP WHERE STATE='ACTIVE'
                    """)) {
                update.executeUpdate();
            }
            return null;
        });
    }

    /**
     * 先记录不可恢复的授权意图，再开放本机代理。
     *
     * @param grant 已绑定 Turn 和执行操作的短期授权
     */
    public void open(CommandNetworkGrant grant) {
        execute(connection -> {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO CORE.CODING_NETWORK_GRANT
                    (ID,TURN_ID,OPERATION_ID,GRANT_JSON,STATE,CREATED_AT,UPDATED_AT) VALUES (?,?,?,?,'ACTIVE',?,?)
                    """)) {
                insert.setString(1, grant.id());
                insert.setString(2, grant.turnId().toString());
                insert.setString(3, grant.operationId());
                insert.setString(4, json.encode(grant).json());
                insert.setObject(5, now());
                insert.setObject(6, now());
                insert.executeUpdate();
            }
            return null;
        });
    }

    /**
     * 在隧道已关闭后记录最终使用；重复关闭只更新相同已观察字节。
     *
     * @param id 本机租约标识
     * @param bytes 双向已转发字节数
     */
    public void close(String id, long bytes) {
        execute(connection -> {
            try (var update = connection.prepareStatement("""
                    UPDATE CORE.CODING_NETWORK_GRANT SET STATE='REVOKED',TRANSFERRED_BYTES=?,UPDATED_AT=? WHERE ID=?
                    """)) {
                update.setLong(1, bytes);
                update.setObject(2, now());
                update.setString(3, id);
                if (update.executeUpdate() != 1) {
                    throw new PersistenceException("网络租约记录不存在");
                }
            }
            return null;
        });
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private <T> T execute(H2Transactions.SqlWork<T> work) {
        try {
            return transactions.execute(work);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new PersistenceException("网络租约事务失败", failure);
        }
    }
}
