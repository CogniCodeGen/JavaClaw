# 统一 Coding 本机构建与验收记录

2026-09-07，平台 `macOS-26.5.2-arm64-arm-64bit-Mach-O`，JDK 25。当前工作树从 clean 执行完整 Reactor：
**15 个 project 全部 BUILD SUCCESS；2316 项测试，0 失败、0 错误、25 项条件跳过。**
跳过项不计为通过，完整清单见[机器可读记录](unified-coding-build-validation.json)。

```bash
mvn spotless:apply clean verify spotless:check checkstyle:check
```

执行日志：`/tmp/javaclaw-unified-coding-clean-verify-final.log`；SHA-256：
`37aaece8a052376280d9bfa344bc70d7f4f7f110a9b0dd900206704eee25f266`。Spotless、Checkstyle、依赖分析、包循环、覆盖率、Desktop Failsafe Golden 和发行构建均使用
项目原有门禁。没有关闭规则、降低覆盖率、调用付费模型或自动提交工作树。

## 模块结果

测试数包含对应模块的 Failsafe；不重复累计此前聚焦回归。覆盖率来自这次 clean 的 JaCoCo XML。

| 模块 | 测试 | 跳过 | 行覆盖率 | 分支覆盖率 |
| --- | ---: | ---: | ---: | ---: |
| javaclaw-api | 96 | 0 | 93.15% | 80.55% |
| javaclaw-protocol | 139 | 0 | 91.50% | 82.21% |
| javaclaw-extension-spi | 60 | 0 | 94.72% | 82.07% |
| javaclaw-agent-runtime | 42 | 0 | 93.33% | 80.70% |
| javaclaw-model-adapters | 63 | 0 | 88.76% | 73.87% |
| javaclaw-builtin-contracts | 63 | 0 | 82.22% | 72.46% |
| javaclaw-builtin-extensions | 164 | 0 | 90.09% | 70.98% |
| javaclaw-app-server | 955 | 6 | 91.41% | 80.62% |
| javaclaw-native-hosts | 198 | 17 | 82.26% | 71.16% |
| javaclaw-browser-service | 47 | 0 | 81.67% | 71.10% |
| javaclaw-knowledge-worker | 7 | 0 | 92.59% | 76.67% |
| javaclaw-client | 109 | 0 | 87.39% | 71.26% |
| javaclaw-desktop | 285 | 0 | 88.27% | 73.49% |
| javaclaw-packaging | 88 | 2 | 82.63% | 76.67% |

Desktop 的一项 Failsafe 对 54 张 Golden 逐字节比较；Coding 导航的参考变化审阅见
[Golden 记录](coding-settings-golden-review.md)，新增运行状态的三个真实 JavaFX 控件截图见
[客户端状态记录](coding-client-state-review.md)。它们不代表其他平台视觉通过。

## 单独执行的实际验收

- 同一 Thread 的聊天、真实 NPM 准备、修改、断网测试失败、修复、成功和继续聊天，已由
  `PublishedCodingHarnessTest` 通过真实 Harness、H2、CAS、托管 Node/npm 与 Seatbelt 验证。正式聚焦日志为
  `/tmp/javaclaw-coding-stream-focused.log`，Server 48 项通过、0 跳过；固定模型替身没有伪造进程结果。
- npm、pnpm、pip 和 Maven 的真实公开依赖准备与后续新断网进程通过；Maven 另从空依赖缓存复验。
  [公开仓库记录](coding-public-repository-acceptance.md)列出版本、命令路径、原始日志和 Gradle 失败边界。
- Maven 官方完整生产安装 Job 已通过下载、SHA、READY、幂等和重开租约；其他七个不同归档完成有界真实 GET
  前缀探测。[安装记录](coding-toolchain-installation-validation.md)区分完整安装与前缀证据。
- PTY 权限监视器清理与 UTF-8 分页分别有旧字节码失败、新实现通过的确定性回归；新测试已纳入本次完整门禁。
  前一次 clean 唯一失败是旧测试期待把 UTF-8 短页显示为替换字符；已同步完整字符与真实坏字节断言，生产策略未放宽。

公开仓库验收在默认本机构建中按显式属性跳过，以上另行实际执行的结果不重加到全仓测试总数。
五 Runner CI 已显式启用完整公开依赖及 NPM Harness 验收；工作流存在不等于远端已执行。

## 兼容与工作区

开工前已保存 `/tmp/javaclaw-unified-agent-baseline-20260907` 的 1919 个文件基线，原有文件未删除。
14 个 Maven 模块、App Server 唯一组合根和 Desktop 的 SDK 边界保持；Protocol v3 现有严格 Schema 原文保持，
26 份已有严格 Schema 与基线逐字节相同；方法目录增加两个容量 RPC，总数 159。六份 V001 migration 与开工基线逐字节相同；新增有序 V002–V004。
数据继续位于 data-v6，不迁移 data-v5；升级回退采用升级前备份，不承诺旧二进制直接读取新版库。

## 仍未满足的整体交付条件

1. macOS Gradle 完整编译与测试需要动态本机 UDP/TCP，当前精确代理端口隔离不能满足；尚未批准引入独立虚拟化环境
   或缩减该平台范围。没有通过允许整个 localhost 绕过限制。
2. Windows 文本替换使用保留实际旧对象的两次移动，目标可能短暂缺失；尚未达到单次原子可见替换要求。
3. macOS x64、Linux arm64/x64、Windows x64 的原生与安装回执未取得。Windows MSVC、签名服务、SCM/WFP/Job/ACL/
   ConPTY、崩溃清理、升级和卸载仍需实际 Runner；平台条件跳过不能替代这些验收。
4. 真实模型效果与完整 Turn 性能没有对照评估；本次没有付费模型结果，也不宣称相应效果或性能提升。

因此，本机软件质量门禁通过不代表原方案的全部五 Runner、原子文件替换与五类工具链发行目标已经完成。
用户入口见[编程使用说明](../coding.md)，架构与当前状态见[验收矩阵](../architecture/acceptance-matrix.md)。

## 本机发行产物

[macOS arm64 ZIP](/Users/fengs/Documents/openProject/JavaClaw/javaclaw-packaging/target/release/javaclaw-6.0.0-SNAPSHOT-mac-aarch64.zip)，443,610,447 B，SHA-256：
`69dd843c92048abf8960a480be22ab0fbf12fa2c83e64e60e2b7511974c526cb`。发行和内容 manifest、SBOM 的大小及摘要已写入机器可读记录。
本机归档构建成功不代表签名、公证或安装验收通过。
