package com.javaclaw.platform.data;

import org.springframework.dao.DataAccessResourceFailureException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 根 Context 的一次性 schema 初始化器。
 *
 * <p>{@link #initialize()} 幂等且线程安全；只有完整执行全部幂等 DDL 后才标记成功，
 * 因此失败后可以由下一次显式调用重试。这里只执行与当前 schema 同步的幂等列补齐和
 * 安全默认值回填，不承担跨大版本业务迁移。</p>
 */
public final class SchemaInitializer {

    private final DataSource dataSource;
    private final JavaClawSchema schema = new JavaClawSchema();
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public SchemaInitializer(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public synchronized void initialize() {
        if (initialized.get()) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            schema.initialize(connection);
            initialized.set(true);
        } catch (SQLException failure) {
            throw new DataAccessResourceFailureException("初始化 JavaClaw 3 schema 失败", failure);
        }
    }

    public boolean isInitialized() {
        return initialized.get();
    }
}
