# 冗余清理与默认目录调整验证

本轮基于 `dev_v0.3.5` 的 `721c9692` 实施，尚未提交。环境为 macOS arm64、Oracle JDK 25、Maven 3.9.0。

## 变更边界

- 删除确认没有生产调用或动态注册的 14 个类／资源文件、废弃提示词常量及仅覆盖旧预览组件的测试。当前 AWT 预览、诊断链路、任务恢复、附件快照和公开工具保留。
- 活跃全局提示词正文及注入方法与基线逐字一致。SDK、协议、Runner 和 Deliverance 源码未变，不引入 Jinfer。
- 默认目录统一为 `data/`、`plugins/`，同步启动配置、测试配置、三个平台装配脚本及当前文档。保留格式标记 `.javaclaw-format=5`、目录防护和锁机制。
- Echo 根目录 `plugin.json` 是包外工具与包内运行时的唯一清单来源；保留发布脚本的 `--offline` 参数。
- 用户明确旧数据无需保留，由用户手动处理。程序不读取、迁移、覆盖或删除旧版本目录。本轮没有创建仓库根的真实 `data/`、`plugins/`，旧 `data-v5/`、`plugins-v2/` 目录元数据未变化。

## 自动验证

先分别清洁构建协议、SDK 和 Runner，再清理 Desktop 输出，避免残留类或资源掩盖删除遗漏。最终命令：

```bash
mvn -o --batch-mode --no-transfer-progress -pl javaclaw-desktop clean
mvn -o --batch-mode --no-transfer-progress -Pui-test \
  -Djavaclaw.distribution.directory="$PWD/target/cleanup-20261012-gqs6wmiz/distribution" \
  verify
```

| 范围 | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| SDK | 3 | 0 | 0 | 0 |
| Runner | 15 | 0 | 0 | 0 |
| Desktop | 2600 | 0 | 0 | 10 |
| 聚合 | 2618 | 0 | 0 | 10 |
| Hello 独立 clean verify | 5 | 0 | 0 | 0 |
| Feishu 独立 clean verify | 5 | 0 | 0 | 0 |
| Deliverance 独立 clean verify | 32 | 0 | 0 | 0 |
| Echo 单元测试 | 2 | 0 | 0 | 0 |
| Echo 真实 Runner IT | 2 | 0 | 0 | 0 |
| macOS 内置 Java + Runner 的 Echo IT | 2 | 0 | 0 | 0 |

最终完整 `verify` 为 `BUILD SUCCESS`，没有使用忽略失败、自动重试或跳过失败用例的参数。10 项跳过沿用原条件：9 项原生 API／macOS FFM 冒烟需显式开启，另 1 项 Windows 捕获测试不适用于 macOS。

| 覆盖率组 | Line | Branch | 原阈值 Line / Branch |
| --- | ---: | ---: | --- |
| 整体 | 70.33% | 50.07% | 55% / 45% |
| application + platform.execution | 89.09% | 70.88% | 80% / 70% |
| Controller | 81.07% | 60.35% | 70% / 60% |

三组覆盖率以及架构、源码卫生、FXML 和功能清单门禁通过，未修改阈值或豁免。目录、格式、符号链接、文件占位、旧目录保留、锁移交及随附导入账本测试通过；真实 AWT 预览两项通过。

首轮全量验证保留了两处失败记录：FXML 数量断言仍为删除前的 141，现已按删除两个废弃页面同步为 139；原生预览校验期间系统鼠标位置发生变化。随后两项定向复核共 7 个用例通过，再次清洁输出的完整聚合验证通过，未修改预览实现或弱化其断言。

## 发行与文件校验

- 两个 Bash 脚本语法、`--offline` 接受和未知参数拒绝验证通过；POM XML 解析通过。
- Echo JAR 中唯一的 `/plugin.json` 与根清单字节一致，SHA-256 为 `010d5c48c95b8292999ab1c84a780661e87d8f50d0d411eaa6b8f98755d9ec39`。
- 新隔离发行目录扫描通过：Desktop 159 个 JAR、Runner 1 个 JAR、四插件 4 个 ZIP／4 个 JAR。
- macOS 开发镜像装配成功，应用与内置 Java 的 ad-hoc strict 签名验证通过。运行时只读，外层根及 `data/plugins` 可写；主程序、158 个依赖、Runner、四插件及完整性清单与构建输入逐字节一致。
- 从 `/private/tmp` 使用新镜像的内置 Java 执行目录探针，确认根据发行 JAR 定位应用根，初始化 `data/plugins`、写入格式 5 并移交数据锁；未创建带版本后缀的目录。这是目录启动链验证，未启动完整 Launcher GUI。
- 通用发行 ZIP 中运行时文件与隔离组装目录逐字节一致，不包含用户数据目录；ZIP SHA-256 为 `7f42a8160a4afd3b6432a01f0f9055c6eca571a0212bbfe639f696eab5fca790`。

## 证据与限制

本地工件与日志位于 `target/cleanup-20261012-gqs6wmiz/`，首次失败、定向复核和最终聚合报告分别归档于 `evidence/first-attempt/`、`evidence/focused/` 和 `evidence/final/`，避免后续专项覆盖完整门禁结果。2260 个源码、资源、POM、脚本、清单与 workflow 输入的指纹为 `924e4e0875b3123aef6e27da6fe8fce0281aa5a92bcc6fd5f198a8ec3472cc91`，逐文件清单在 `evidence/build-input.json`。

本机没有 `pwsh`，未执行 Windows PowerShell 语法解析；未在 Windows／Linux 实机装配或运行。本轮也未执行 Developer ID 签名／公证、完整人工 GUI、Feishu 真实账号或 Deliverance 真实权重验收，这些结果不由本轮自动验证替代。
