# 全量格式与注释规范化验收记录

验证日期：2026-08-28。此次在现有未提交工作树上增量整理，没有恢复旧源码、迁移数据或提交 Git。

## 交付范围

- 覆盖 9 个 Maven 模块的 377 个生产 Java 文件和原有 57 个测试 Java 文件；新增 `CodingStyleRulesTest` 后共有 435 个 Java 文件。
- 统一 Java 排版、显式 import、控制块与方法留白，补充中文公共契约、Record 组件和关键逻辑说明；准确的既有英文说明保留。
- 新增项目级 `AGENTS.md`、`.editorconfig`、`.gitattributes`、编码规范和 Checkstyle 配置。
- Spotless 3.10.0 / Palantir Java Format 2.97.0（PALANTIR）、Maven Checkstyle Plugin 3.6.0 / Checkstyle 14.0.0 固定版本。
- 两项检查绑定 `validate`，CI 的所有原生平台使用相同检查命令；普通构建只检查，不自动修改文件。
- Checkstyle 引擎用于构建及门禁样例测试，不进入产品运行依赖。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| `mvn spotless:check checkstyle:check` | 全 Reactor 通过，0 个 Checkstyle 违规 |
| 重复 `mvn spotless:apply` | 474 个参与比对的源码、资源和配置文件 SHA-256 完全一致 |
| 公共契约盘点 | 公共类型、非继承的公共/受保护方法与构造器、Record 组件均无缺失 |
| 格式规则正反例 | 8 个测试通过；覆盖通配符 import、缺失文档、Record 组件、压缩语句、必要大括号、中文、继承文档、模式 switch、JPMS 和 `@Generated` |
| 架构门禁 | 14 个测试通过；使用 JDK 语法树规整源码，保留字符串空格，不把 Javadoc 当作真实依赖 |
| 完整发布前 `verify` | BUILD SUCCESS；53 个测试类，共 171 个测试，170 通过、0 失败、0 错误、1 个非本平台测试跳过 |

完整命令：

```bash
mvn clean -Djavaclaw.require.native.sandbox=true \
  -Djavaclaw.performance.gate=true verify
```

本次完整运行耗时约 51 秒。唯一跳过项为 Windows 专属的
`windowsAppContainerAllowsOnlyTheGrantedWorkspaceAndProtectsGitMetadata`，本机为 macOS。
当前平台的原生沙箱要求已开启，没有通过关闭原生门禁来取得通过结果。
Linux、Windows 原生行为仍须由各自 CI Runner 验证，本记录不代表这些平台已在本机验证。

## 语义与资源保真

整理前后分别使用 JDK 25 AST 提取并比较生产源码，结果一致：

- 11,332 个字面量的解析值相同，包括 Java 字符串和文本块。
- 3,269 个生产类型/方法声明的参数、返回类型、可见性及异常列表相同。
- 原有业务测试的字面量相同；架构扫描测试为支持 AST 检查新增了测试辅助代码与断言。
- 11 份 POM/FXML 在格式化前后的元素顺序、属性与有效文本一致；质量插件及测试依赖的新增配置另行审阅。
- 两份历史 migration、两份 JavaClaw 协议 Schema 和 MCP 官方 Schema 快照的 SHA-256 均未改变。
- `git diff --check` 通过。

受保护资源没有被格式化；golden 协议断言继续通过。此次未修改 JSON-RPC、H2 Schema、领域公共 API、业务调用链或进程隔离策略。

## 性能门禁

同一 120 ms fake model、4 次预热、40 次采样，普通 Turn 与单工具 Turn 的协议增量均低于既有 10% 上限：

| 场景 | p50 增量 | p95 增量 |
| --- | ---: | ---: |
| 普通 Turn | 2.25% | 3.49% |
| 单工具 Turn | 0.88% | 1.36% |

原始报告由测试生成于 `javaclaw-client/target/performance-gate.json`；下次 `clean` 会清理该构建产物。
后续开发按 [编码规范](coding-style.md) 和根 [AGENTS.md](../AGENTS.md) 执行检查，并在行为变化时同步维护相关注释。
