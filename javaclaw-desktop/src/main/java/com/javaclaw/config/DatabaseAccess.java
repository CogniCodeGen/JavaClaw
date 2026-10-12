package com.javaclaw.config;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 应用数据库连接入口。
 *
 * <p>生产环境由 Spring 根 Context 的 DataSource 适配器提供，测试可注入显式目录的
 * H2 实现；调用方不得通过全局系统属性定位连接。</p>
 */
public interface DatabaseAccess {

    Connection open() throws SQLException;

    /** 用于诊断日志的数据库描述，不得包含凭据。 */
    String description();
}
