# JavaClaw 代码格式与注释规范

## 适用范围

全部 9 个模块的生产、测试和构建配置遵守本规范。质量要求适用于每次开发，不因代码由人工、工具或 AI 生成而降低。
根 `AGENTS.md` 是协作入口；本规范、父 POM 和 `.editorconfig` 共同定义可执行约定。
`.gitattributes` 仅将维护中的 Java、POM、FXML 和质量配置固定为 LF，避免 Windows checkout 引入格式漂移。

## 格式

- Java 使用 UTF-8、LF、4 空格缩进和 120 列换行目标。不可拆分的 URL、字符串及文本块以语义保真为先。
- 一行一条语句，控制流程使用大括号，非空方法体展开，方法之间留一行空白。
- import 明确到类型或静态成员，按 JDK、第三方、JavaClaw、static 分组排序；禁止通配符和未使用 import。
- 参数、链式调用、泛型和注解交给 Spotless 3.10.0 + Palantir Java Format 2.97.0（PALANTIR）排版。
- POM/FXML 使用 Spotless 的 Eclipse WTP 4.21.0 XML 格式化器，保留元素顺序，只整理缩进、换行与空白；不改变插件执行顺序、字段初始化顺序或 UI 布局。
- 不通过重排成员、改变调用链或扩大接口来迎合格式化器。结构重构必须有独立的行为依据与测试。

## 注释

新增和重写的注释使用中文，Thread、Turn、Item、Schema、FFM 等术语以及代码标识保留英文。
官方文档引文、版权与许可证保持原文；已有准确注释不因语言不同而被机械替换。

公共类型和公共/受保护方法、构造器必须有 Javadoc。文档应回答使用者真正需要的问题：

- 操作的边界是什么，会读取或改变什么状态？
- 参数是否允许空值、单位是什么、revision 和幂等键如何使用？
- 返回空值、false、Optional.empty 或异常分别意味着什么？
- 谁负责关闭资源、取消执行、处理超时及恢复订阅？

Record 在类型注释的 `@param` 中说明组件含义及单位，不重复描述自动生成的 accessor。
`@Override` 实现可继承已有接口契约；实现增加权限校验、事务或资源约束时，补充 `@implNote`。

```java
/**
 * 读取持久事件游标之后的事件，不包含仅在内存中传输的 token delta。
 *
 * @param afterSequence 已确认的持久 sequence，0 表示从首条事件开始
 * @param limit 本次最多返回的事件数，必须为正数
 * @return 按 sequence 升序排列的事件；没有新事件时返回空列表
 */
List<ThreadEvent> eventsAfter(ThreadId threadId, long afterSequence, int limit);
```

并发、事务、取消、背压、模型预算、缓存失效、权限撤销、原生资源和 OAuth 必须解释关键不变量。
说明“为什么这样做”，不要写“设置变量”“执行方法”等重复代码的句子。注释随行为一起修改；不以注释密度代替质量。
测试以场景命名表达意图，只给复杂夹具、故障注入和安全断言补充说明。

## 本地与 CI

```bash
# 仅在开发者主动执行时改写源码
mvn spotless:apply

# 两个检查都绑定 validate；常规构建不自动改写文件
mvn spotless:check checkstyle:check

# 全量回归（平台安全测试在相应原生 Runner 上执行）
mvn clean -Djavaclaw.require.native.sandbox=true \
  -Djavaclaw.performance.gate=true verify
```

Checkstyle Maven Plugin 3.6.0 使用固定的 Checkstyle 14.0.0，仅作为构建依赖。
测试源码只豁免 Javadoc 存在性和组件文档检查，其余规则与生产代码一致；禁止新增整模块豁免。
纳入维护的 `@Generated` 类型不豁免公共文档规则，`@Override` 方法可以继承父契约。
`target`、第三方代码、官方 Schema、golden fixture 和历史 migration 不得参与格式重写。
格式化 Java 文本块时必须保持解析后的内容；格式与行为变更应分开审阅。

`CodingStyleRulesTest` 使用真实 Checkstyle 配置验证正反样例，包括中文 Javadoc、Record、继承文档、模式 switch 和 JPMS。
故意违规的 import 反例保存在 `src/test/resources/coding-style/*.java.txt`，测试时再复制成 Java 源码；这些夹具不参与格式化。
`ArchitectureBoundaryTest` 通过 JDK 语法树规范化源码后检查依赖和进程入口，不依赖某一种换行或缩进。

工具配置依据：[Spotless Maven](https://github.com/diffplug/spotless/tree/main/plugin-maven)、
[Palantir Java Format](https://github.com/palantir/palantir-java-format)、
[Checkstyle 公共方法文档规则](https://checkstyle.org/checks/javadoc/missingjavadocmethod.html)。

每次交付前确认格式幂等、注释与行为一致、相关测试通过，并说明尚未验证的平台。
本规范不安装 Git hook、不修改用户全局设置，也不要求自动提交工作树。
