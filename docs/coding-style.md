# JavaClaw 5 代码质量规范

本规范适用于 14 个模块的手写生产代码、测试、构建配置与纳入维护的资源。根 `AGENTS.md`、
`.editorconfig`、Checkstyle 和 Maven verify 共同构成可执行约束。

## 可读性与结构

- Java 使用 UTF-8、LF、4 空格缩进和 120 列目标；禁止通配符 import、一行多语句与压缩非空方法体。
- 手写 Java、SQL、CSS、FXML 与发布脚本单文件最多 600 行，目标不超过 400 行；Java 单方法最多 60 行。
- cyclomatic complexity 不超过 12，控制结构嵌套不超过 4 层，参数不超过 7 个。
- 参数过多时使用有语义的不可变 command/options record；不得用无意义 Map 代替模型。
- 一个类只有一个主要变化原因。禁止 `Utils`、`Common`、万能 `Manager`、Service Locator 与全局静态容器。
- 接口只用于模块边界、可替换实现或外部副作用；不为单一内部调用者机械创建接口。
- JPMS 只导出公共契约包；实现包不 export。模块和包依赖必须无环。

Spotless 负责 Java 与 XML 的确定性格式。构建默认只检查，不修改源码；开发者主动执行
`mvn spotless:apply` 后必须检查 diff。

## 注释与契约

新增或重写注释使用简单中文，Thread、Turn、Item、Schema、FFM 等术语保留英文。

- 公共类型、公共/受保护方法和构造器必须有准确 Javadoc。
- Record 在类型文档说明组件含义、单位与可空性，不重复注释自动 accessor。
- 注释解释边界、原因、不变量、资源所有权和失败语义，不翻译代码。
- 并发、事务、取消、背压、预算、权限撤销、缓存、FFM、OAuth 与副作用恢复必须用类型说明或
  `@implNote` 记录关键不变量。
- 行为改变时同步更新注释并删除过时说明。
- 生产与测试代码不得保留无负责人和验收条件的 TODO/FIXME。

## 依赖与测试

- 根构建执行 dependency convergence、禁用 JNA/JNI，并在 verify 执行 unused/undeclared dependency 分析。
- 新依赖必须放在承担该职责的模块，并评估发行体积与进程边界。
- 测试名称表达业务场景；并发、安全、事务与故障恢复必须覆盖失败路径。
- 每个含生产代码的模块必须至少执行一个测试；删除全部测试时 Surefire 必须使构建失败，覆盖率不得因缺少
  execution data 而静默跳过。
- `api`、`protocol`、`extension-spi`、`agent-runtime`、`app-server` 的目标覆盖率为行 90%、分支 80%。
- Adapter、Desktop、Native Host 的目标为行 80%、分支 70%，原生行为由对应 OS Runner 统计。
- 不允许降低门槛、整模块 suppression 或以排除核心类制造虚假覆盖率。

## 交付命令

```bash
mvn spotless:apply
mvn spotless:check checkstyle:check
mvn clean verify
```

原生发布还必须在五个 Runner 上启用 `-Djavaclaw.require.native.sandbox=true` 与性能门禁。交付说明应列出
通过的命令、未验证平台和真实限制；不得把跳过测试或跳过覆盖率的构建称为发布验收。
