# 本地数据库密钥验收（2026-09-20）

## 本轮范围

- 生产 `AppServerBootstrap` 经 `PlatformFoundationFactory.createLocal` 使用 `DatabaseMasterKeyProtector`，
  API Key 等业务秘密的 AES-256-GCM 密文及主密钥均位于本地 H2；不访问系统钥匙串。
- 按最终确认，仅支持全新数据库及其后续重启。生产入口 `initializeLocalVault` 以只读、禁止创建的连接检查已有库；
  旧版本或残缺结构明确失败，不升级、不导入、不清空。已有用户数据目录没有被本轮操作修改。
- 新库通过现有版本管理机制创建 V011 本地主密钥表；无旧凭据迁移或迁移状态表。V001～V010 原文保持不变。
- Desktop 仍经 Presenter / SDK / RPC 调用服务端。原 Vault 候选准备、密文与回执提交、运行时切换、清理顺序，以及
  Provider 版本校验、凭据反重放、Harness、Turn 和 Sandbox 均保持既有机制；没有新增依赖。
- 主密钥与密文同库，数据库副本包含解密材料。数据目录和备份访问权限是该存储模式的保密边界。

## 关键回归

| 测试 | 覆盖内容 |
| --- | --- |
| `DatabaseMasterKeyProtectorTest` | 本地读写、替换、重复删除、数组隔离、非法参数、SQL 异常脱敏及驱动跟踪禁止泄漏密钥 |
| `DatabaseVaultPersistenceTest` | 重启解密、轮换/重置幂等、提交失败回滚与候选清理、缺失主密钥不重建 |
| `LocalVaultDatabaseInitializationTest` | 新库初始化与重启；旧库拒绝后数据库字节、目录文件列表、业务行和历史均不变；残缺/未来版本拒绝 |
| `LocalVaultMigrationTest` | 新表约束、checksum、通用建表执行器的 pending 与部分 DDL 恢复；不代表生产支持旧库升级 |
| `LocalVaultFoundationTest` | 生产组合根保存完整 Provider 与密钥并重启读取；显式 Locked 注入不被默认存储覆盖 |
| 既有 Vault / Provider / Desktop 回归 | 事务、双版本冲突、运行时撤权、秘密清理、保存反馈和弹窗交互 |

## 验证状态

- `mvn spotless:apply`：通过，已检查实际 diff。
- `mvn spotless:check checkstyle:check`：通过。
- 定向测试：209 项，208 项执行通过，1 项真实系统凭据测试按既有条件跳过，0 失败、0 错误。
- 完整 `mvn clean verify`：通过，15 个构建项目全部成功，耗时 21:40 min。3601 项测试中 3575 项执行通过、
  26 项按既有条件跳过，0 失败、0 错误；覆盖率、模块依赖与架构边界门禁均通过。详见 [validation.json](validation.json)。

完整构建期间的独立假数据实验发现：异常列长度会使 H2 默认错误跟踪记录写入值。已关闭文件与终端 SQL 跟踪，
并补充回归；先前构建主动中断，最终门禁从头运行，不将中断的运行计作通过。

## 限制

仅在 macOS arm64、JDK 25 运行本轮验证；Windows、Linux、macOS x64 未在目标机器验证。
未调用付费模型，未升级、重置或迁移用户实际数据库。旧系统凭据条目不读取、不删除。
只读版本预检与正式打开之间不宣称有原子文件替换防护；尚未完成 V011 建表的新目录不自动续建，应使用另一空目录。
