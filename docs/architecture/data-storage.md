# 数据与存储

JavaClaw 3.0 使用单一数据根 `data/`。格式验证在数据库、JavaFX 和后台服务启动之前完成，防止
新代码误读或改写旧格式数据。

## 路径解析与格式

`DataRoot.resolve()` 按以下优先级解析目录：

1. 非空系统属性 `-Djavaclaw.data.dir=<目录>`；
2. 未显式配置时使用 `<user.dir>/data`。

解析结果转换为绝对规范路径。数据根必须包含普通文件 `.javaclaw-format`，去除首尾空白后的
内容必须精确等于 `3`。

| 目录状态 | `DataRoot.prepare()` 的结果 |
|---|---|
| 路径不存在 | 创建目录并原子写入格式标记 |
| 已存在且为空 | 原子写入格式标记 |
| 非空，标记为 `3` | 接受并继续启动 |
| 路径是普通文件 | 拒绝启动 |
| 非空但缺少标记 | 拒绝启动 |
| 标记不是 `3` | 拒绝启动 |

准备操作幂等；失败不会删除、改名或迁移任何既有内容。显式路径只覆盖位置，不绕过格式检查。

## 目录结构

```text
data/
├── .javaclaw-format
├── javaclaw.mv.db
├── javaclaw.instance.lock
├── javaclaw.instance.endpoint
├── knowledge/
├── memory-stores/
├── skills/
├── logs/
└── screenshots/
```

`javaclaw.instance.lock` 和 `javaclaw.instance.endpoint` 是单实例运行协调文件，不是业务数据。
应用完全退出后它们可以被下次启动复用或重建。

## H2、schema 与事务

根 Context 创建一个 `H2DataSource`，数据库文件为 `<data-root>/javaclaw.mv.db`。连接优先使用 H2
`AUTO_SERVER` mixed mode；仅在明确的 AUTO_SERVER 或本地套接字能力错误时永久回退 embedded。
认证、磁盘和普通 SQL 配置错误原样抛出，不能用回退掩盖。

`SchemaInitializer` 在根 Context 装配期间执行幂等 DDL，并保证同一实例只初始化一次。结构化
Repository 通过共享 `JdbcTemplate` 访问数据，事务边界由 `DataSourceTransactionManager` 管理。
3.0 不引入 Flyway，也不维护旧 schema 的增量迁移链。

## 工作区隔离

结构化记录使用 `workspace_id` 隔离；工作区运行时持有不可变路径快照。知识、记忆、浏览器状态、
日志和截图等文件资产使用工作区子目录。技能本体可以全局共享，但使用统计、提案和启用状态按
工作区管理。

工作区切换不会原地修改运行中对象的路径。候选子 Context 创建成功后才替换旧 Context，避免
一次操作跨越两个工作区。

## 测试数据

- 普通测试：`target/test-data`
- `ui-test` profile：`target/ui-test-data`
- 真实运行：项目根 `data/`，已被 `.gitignore` 排除

测试和本地 UI 验证不得使用真实数据根。

## 备份、升级与回滚

备份必须在 JavaClaw 完全退出后复制整个 `data/`，不能只复制 H2 文件。只恢复数据库会遗漏知识、
记忆、技能和其他文件资产，造成引用不一致。

2.x 数据不自动迁移。升级前应把旧目录完整备份并移出默认路径，让 3.0 创建新格式目录。回滚时
先保存 3.0 数据，再恢复旧应用与旧格式备份；旧版本不得直接打开格式 3 数据。完整步骤见
[升级到 3.0](../upgrade-3.0.md)。
