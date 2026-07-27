package com.javaclaw.config;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 应用数据库连接入口。
 *
 * <p>生产环境由 {@link AppDatabaseAccess} 提供，测试可注入临时 H2 实现，避免通过
 * 修改全局 {@code user.dir} 重定向数据库。</p>
 */
public interface DatabaseAccess {

    Connection open() throws SQLException;

    /** 用于诊断日志的数据库描述，不得包含凭据。 */
    String description();
}
