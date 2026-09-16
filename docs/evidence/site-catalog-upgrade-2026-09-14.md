# Site 旧库启动兼容修复（2026-09-14）

## 问题与修复

新增浏览器工具时，Site 的工具集合从 2 项变成 10 项、最大风险从 `NETWORK` 变成 `EXTERNAL_EFFECT`，但清单仍由通用文档资源生成 `5.0.0 / revision 1`。已有数据库中的完整旧清单与新版清单不同，因此 `ExtensionCatalogRepository` 正确拒绝静默替换，导致内置扩展启动失败。此前缺少这一旧库升级路径。

Site 现使用显式的 `6.0.0 / revision 2` 描述，权限上限 revision 同步升到 2。`BuiltinCatalogMigrations` 冻结目标和两份已审核历史清单，宿主显式把前驱交给目录仓储；原单参数安装接口仍不允许任何差异升级。

仓储在同一事务内校验完整历史描述、行身份、信任与版本，并使用旧 revision 和旧描述作为条件写入。只更新清单、清单版本和不倒退的更新时间，保留启停状态、状态版本、原命令回执及所有托管数据。未声明的差异、损坏、第三方身份及降级继续拒绝。旧 Turn 的工具身份 revision 1 在升级后失效，不能调用 revision 2 的能力。

不需要删除数据库或编辑用户数据。重新构建并启动修复版本后，已知旧清单自动迁移，后续启动保持幂等。

## 验证

验证使用 macOS 26.5.2 arm64、JDK 25；全部数据库位于测试临时目录，未打开或修改用户实际数据库，也未调用付费模型。

- 新增 5 项 Bundle 清单测试：精确目标与两个前驱、全部 10 个 Site 工具的新身份、未知描述拒绝、其他 Bundle 保持旧行为。
- 新增 6 项目录事务测试：单调升级、旧身份拒绝、禁用与原幂等回执保留、损坏和未知描述拒绝、集合语义及时间回拨。
- 新增 2 项真实 App Server 重启测试：启用和禁用旧库各连续启动两次，逐字核验网站、账号及迁移标记的 payload、revision、更新时间不变，并通过扩展 RPC 读取或确认禁用拒绝。
- Site 工具集成夹具改为从当前模型可见的冻结目录读取 ToolIdentity，不再写死旧 revision；原审批、拒绝、取消及网络边界断言保留。
- 定向 reactor 回归 145 项全部通过，其中服务端 74 项；0 失败、0 错误、0 跳过。
- `mvn -o spotless:apply`、实际 diff 检查、`git diff --check`、`mvn -o spotless:check checkstyle:check` 通过。

完整 `mvn -o -fae verify` 于 19:18 CST 完成，单次 reactor 的 15 个模块全部 `SUCCESS`，构建结果为 `BUILD SUCCESS`。共 3,260 项：3,234 通过、0 失败、0 错误、26 跳过。其中 Built-in Extensions 226 项，App Server 1,222 项（6 跳过），Desktop 717 项加独立 JVM Golden 1 项，Packaging 98 项（3 跳过）。覆盖率、依赖、架构和发行清单门禁均通过，没有放宽规则。

26 项跳过包括 Native Hosts 的 16 项 Windows/Linux 原生验证和 1 项需显式启用的系统凭据设施验证、服务端的 6 项真实 Provider/发行工具链/联网依赖验收，以及 Packaging 的 1 项 Windows 进程监督和 2 项需显式原生镜像环境的 Browser smoke。跳过不作为对应平台或可见浏览器能力已通过的证据。

本机日志：`/private/tmp/javaclaw-site-catalog-regression-final.log`、`/private/tmp/javaclaw-site-catalog-static.log`、`/private/tmp/javaclaw-site-catalog-full-verify.log`。这些是临时日志；本文件保留持久结果摘要。

本修复不改变可见 Chromium 的原生隔离门禁。Windows、Linux 未实机验证，浏览器发布限制仍见 [功能与限制](../browser-v6.md)。
