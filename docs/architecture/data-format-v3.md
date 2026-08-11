# 3.0 数据格式与升级边界

JavaClaw 3.0 使用全新的数据根，不读取、迁移或修改 2.x 数据。格式校验在数据库、JavaFX 和
后台服务启动之前完成，避免新代码误写旧目录。

## 路径解析

`DataRoot.resolve()` 按以下优先级解析目录：

1. 非空系统属性 `-Djavaclaw.data.dir=<目录>`；
2. 未显式配置时使用 `<user.dir>/data-v3`。

显式路径只覆盖位置，不绕过格式检查。解析结果会转换为绝对规范路径，再传给单实例锁、
Spring 根 Context 和所有数据组件。

## 格式标记

数据根必须包含普通文件 `.javaclaw-format`，去除首尾空白后的内容必须精确等于 `3`。
`DataRoot.prepare()` 的接受规则如下：

| 目录状态 | 处理结果 |
|---|---|
| 路径不存在 | 创建目录并原子写入格式标记 |
| 已存在且为空 | 原子写入格式标记 |
| 非空，标记为 `3` | 接受并继续启动 |
| 路径是普通文件 | 拒绝启动 |
| 非空但缺少标记 | 拒绝启动 |
| 标记不是 `3` | 拒绝启动 |

准备操作幂等；失败不会删除、改名或迁移目录中的任何内容。旧 `data/` 始终保持不动。

## 数据库初始化

根 Context 创建一个 `H2DataSource`，数据库文件为 `<data-root>/javaclaw.mv.db`。连接优先使用
H2 `AUTO_SERVER` mixed mode；仅在明确的 AUTO_SERVER 或本地套接字能力错误时永久回退到
embedded 模式。认证、磁盘和一般 SQL 配置错误原样抛出，不能用回退掩盖。

`SchemaInitializer` 在根 Context 装配期间执行幂等 DDL，并保证同一实例只初始化一次。
Repository 通过共享 `JdbcTemplate` 访问数据，事务边界由 `DataSourceTransactionManager`
管理；3.0 不引入 Flyway，也不存在旧 schema 的增量迁移链。

## 升级与回滚

- 升级：直接启动 3.0，使用新的 `data-v3/`；需要的配置和插件按 3.0 规则重新创建或安装；
- 回滚：运行旧版本并继续指向原 `data/`；
- 3.0 已产生的数据应保留，回滚过程不得删除 `data-v3/`；
- `plugin.json.apiVersion` 缺失或主版本不是 3 的插件会在创建插件类加载器前被拒绝。
