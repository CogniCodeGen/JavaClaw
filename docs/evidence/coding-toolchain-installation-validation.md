# 托管工具链生产下载与安装验证

## 结论与范围

2026-09-07 在 macOS arm64、Java 25（25+37-LTS-3491）上，当前发行目录的 8 个不同归档均通过真实生产下载器
到达正文；每个只接收首个不超过 32 KiB 的缓冲后主动停止。Maven 3.9.11 另完成一次真实官方完整下载、SHA-256
校验、持久安装 Job、发布、入口核验与重新打开后的租约验证。该结果证明这些重定向不会使当前安装链必然失败。

本次没有修改生产源码，没有运行 Maven 构建，也没有使用预下载归档替身。驱动独立编译至 `/tmp`，使用已有构建的
生产类和资源；应用状态只写入新建的隔离 `data-v6`。没有执行 Maven 入口或任何项目脚本，没有调用付费模型。

发行资源为 `javaclaw-app-server/src/main/resources/coding/toolchains-v1.json`，本次 SHA-256：
`5b88fdb28946da09e57839087c43c70bc12e7d24ae88311523df350009edd8db`。
目录固定版本与归档摘要，保留官方发布入口；它没有固化 GitHub 返回的临时签名 CDN URL。

## 生产调用链与授权边界

应用组合根 `AppServerRuntimeBootstrap.coding` 注入 `CodingToolchainCatalog.bundled()` 和
`PinnedArtifactDownloader`。设置管理的 `toolchain/install` 请求进入 `ToolchainManager.install`，只接受目录引用；
完整 Job 执行时再次核对冻结制品和目录是否一致。随后调用链为：

```text
ToolchainManager.install
  → H2 安装记录、Extension Job、Outbox 与幂等响应
  → ExtensionJobSupervisor 领取并保存工作单元意图
  → ToolchainManager.InstallerExecutor
  → ToolchainInstaller
  → PinnedArtifactDownloader → SocketBrokerTransport
  → 下载字节预算与 SHA-256 → 解包及完整性证据 → 发布
  → Job COMPLETED / 安装 READY → acquire 再次验证文件证据
```

`PinnedArtifactDownloader` 显式处理 301、302、303、307、308，最多跟随 5 次重定向。“无自动重定向”指不让底层
HTTP 客户端静默跟随，不能解释为拒绝所有跳转。每跳重新要求不含 userinfo 凭据的 HTTPS/443，验证全部 DNS 答案是公网，
再固定地址连接，同时保留该跳主机的 SNI 与 TLS 主机名校验。请求不携带宿主代理、Authorization 或 Cookie。
跨跳使用同一截止时间；最终正文仍受目录字节上限与平台 512 MiB 上限约束。

无需为本次验证放宽普通 Network Broker 或 Command Proxy 的授权，也无需向模型开放下载地址。应继续拒绝不安全
重定向，包括 HTTP 降级、非 443、URI userinfo 凭据与私网解析。初始源及最终归档仍由可信目录和摘要约束。
GitHub 的[官方 release asset 文档](https://docs.github.com/en/rest/releases/assets#get-a-release-asset)也要求客户端处理
直接 200 或 302 下载响应；不应把观测到的签名地址替换为发行目录中的长期地址。

## 8 个归档的真实生产 GET

先以无代理、HTTP/1.1 的 HEAD 核对官方响应，未读取正文；再使用真实 `PinnedArtifactDownloader`、
`BrokerTarget.SystemHostResolver` 与 `SocketBrokerTransport` 发起 GET。记录用传输装饰器只观察状态行、主机及
已验证的地址，不改变 DNS、TLS、请求头或正文解码行为。探测截止缩短为 20 秒；输出目标在首个缓冲抛出专用
`ReachedBody` 异常，生产下载器据此关闭当前 socket。以下字节数只统计交给输出目标的正文，不含 HTTP/TLS 开销。

| 固定制品 | 实际 GET 链 | 收到的正文 | 结果 |
| --- | --- | ---: | --- |
| Temurin JDK 25.0.1+8 | github.com 302 → release-assets.githubusercontent.com 200 | 1,378 B | 预期首块停止 |
| Temurin JDK 21.0.8+9 | github.com 302 → release-assets.githubusercontent.com 200 | 1,378 B | 预期首块停止 |
| Maven 3.9.11 | archive.apache.org 200 | 7,924 B | 预期首块停止 |
| Gradle 9.1.0 | services.gradle.org 307 → github.com 302 → release-assets.githubusercontent.com 200 | 1,378 B | 预期首块停止 |
| Gradle 8.14.3 | services.gradle.org 307 → github.com 302 → release-assets.githubusercontent.com 200 | 1,378 B | 预期首块停止 |
| Node 22.18.0，含 npm 10.9.3 | nodejs.org 200 | 1,369 B | 预期首块停止 |
| pnpm 10.14.0 | registry.npmjs.org 200 | 104 B | 预期首块停止 |
| Python 3.12.11+20250818，含 pip 24.3.1 | github.com 302 → release-assets.githubusercontent.com 200 | 1,378 B | 预期首块停止 |

本次共交付 16,287 B 正文。逐跳公网地址和预期停止原因保存在日志；签名 Location 的查询内容未写入报告。
这些是成功连接和正文解码前缀证据，不是 8 个完整归档的 SHA 或安装成功证明。

## Maven 完整安装 Job

临时驱动复用 `ToolchainManagerTest` 的真实 H2、Workspace 创建与 `ExtensionJobSupervisor` 调度方式，活动计数
租约使用该夹具的空实现。下载端使用生产 `PinnedArtifactDownloader`；外层只计数并独立计算流式摘要，没有提供
替代归档或绕过 Installer。入口是服务端管理安装方法，没有启动 Desktop/SDK/RPC 前端。

- 官方版本：Maven 3.9.11，来源为
  [Apache 归档](https://archive.apache.org/dist/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.tar.gz)。
- 完整下载一次，9,160,848 B，等于该目录条目的下载上限。
- 流式 SHA-256 与生产安装器核验结果均等于发行值：
  `4b7195b6a4f5c81af4c0212677a32ee8143643401bc6e1e8412e6b06ea82beac`。
- 请求首先持久受理；下载没有在 Job 调度前发生。Job `6ce741fd-b193-45cd-b066-356753d4bf3a` 最终为 `COMPLETED`，
  安装为 `READY`，发布后 staging 为空。
- `acquire` 再次核验完整性证据；声明入口 `apache-maven-3.9.11/bin/mvn` 为普通可执行文件，6,184 B。
- 同一幂等安装恢复相同 Job，没有新增下载；关闭并重新创建 Manager 后再次取得经过验证的租约，累计下载仍为一次。
- 完成时间：`2026-09-07T11:53:38.841189Z`（北京时间 19:53:38）。

隔离状态根为 `/private/tmp/javaclaw-production-maven-10040531512155631293/data-v6`。
该目录保留安装内容和 H2 证据；不是用户当前应用的数据根。

## 本地证据与校验摘要

下列是本次工作机的本地文件，不是随仓库发布的自动回归资产。临时目录清理后链接可能失效；SHA-256 用于识别本次
回执内容，不能替代未来平台的重新运行。

| 本地文件 | SHA-256 |
| --- | --- |
| [官方 HEAD 回执](/tmp/javaclaw-catalog-heads.json) | `c401cb95d09ef59f977e74b77ec9a1aa584b3ff613d554c3ce9b8e38f6b20bd9` |
| [生产 GET 逐跳回执](/tmp/javaclaw-catalog-production-probe.log) | `199efa1fd484b00472c503d5e82b1c3ef377814705bb70de36358e8027d1fc87` |
| [Maven 安装日志](/tmp/javaclaw-production-maven-install.log) | `ee9eb7e9989048cd09c3bb59f1d7e80b97f6c9932f28e4693d3d82060ecbe8a5` |
| [Maven 结构化报告](/private/tmp/javaclaw-production-maven-10040531512155631293/probe-report.json) | `26e862955ca44d9602cf22ea8bdf4a0c4028db4ccf4a6bf657bacb350e76c2c9` |
| [小流探测驱动](/tmp/CatalogProductionProbe.java) | `064433046a011160273fff47c643e87b887c06f82600a3ad14dd466690886429` |
| [完整安装驱动](/tmp/ProductionMavenInstallProbe.java) | `2bf7f70794e5b55bd41adae8c4c140cb3f99c2ae409d8c57a7a9b34179d874b6` |

驱动使用当时的 `/tmp/javaclaw-server-test-classpath`，其 SHA-256 为
`193d16d8df2416c175d0536fae18065b03b6aedcd0f9a1a34764aa104edc489c`，独立编译输出至
`/tmp/javaclaw-catalog-probe-classes`；没有覆盖模块 `target`。复核时应先确认生产类和目录版本，再使用上述驱动，
完整安装驱动会新建独立临时数据根并实际联网下载约 9.2 MB，不执行归档入口。

## 未验证范围

- 未操作设置页按钮，也未通过 SDK/RPC 完成一次 UI 端到端安装；本次闭合的是其服务端安装 Job 和下载链。
- 除 Maven 外，其余归档只验证真实 GET 前缀，未在本次进行完整生产下载、SHA 与安装。
- 未执行此次安装的 Maven 入口或任何项目依赖、构建、测试和生命周期脚本。
- 未在 macOS x64、Linux 或 Windows 执行上述探测或安装；不能据此宣布五 Runner 通过。
- 公共下载成功不替代私网/DNS 重绑定拒绝、撤权、Command Proxy 或原生 OS 隔离测试。
- 其他已执行的原生测试与平台限制见[编程网络与原生隔离验证](coding-network-validation.md)。
