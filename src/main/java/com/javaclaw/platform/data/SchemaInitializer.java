package com.javaclaw.platform.data;

import com.javaclaw.config.AppDatabase;
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
 * 因此失败后可以由下一次显式调用重试。3.0 不在这里执行旧数据迁移。</p>
 */
public final class SchemaInitializer {

    private final DataSource dataSource;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public SchemaInitializer(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public synchronized void initialize() {
        if (initialized.get()) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            AppDatabase.initializeSchema(connection);
            initialized.set(true);
        } catch (SQLException failure) {
            throw new DataAccessResourceFailureException("初始化 JavaClaw 3 schema 失败", failure);
        }
    }

    public boolean isInitialized() {
        return initialized.get();
    }
}
