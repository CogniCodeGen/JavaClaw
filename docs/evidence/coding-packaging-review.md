# Coding 发行依赖与许可检查

最终复验已于 2026-09-07 20:32:40 完成：完整 `clean verify` 的 Packaging **88 项测试、86 通过、2 条件跳过**，
许可、依赖分析、包循环、覆盖率、jlink、ZIP 及两层 manifest 生成与验证全部通过。跳过项分别为 Windows
Supervisor 实机检查及未启用的真实 Browser/Sandbox smoke，不能记为平台通过。
本次完整命令、各模块结果及日志 SHA-256 见[最终构建记录](unified-coding-build-validation.md)。

## 早期检查与修复依据

2026-09-07 的正式 Packaging 聚焦构建执行了 87 项测试：85 项通过、2 项按平台条件跳过，0 失败、0 错误。
其中 `ProductionPackageCycleTest` 验证了 Native 与 Server 包迁移后的实际源码依赖图。关联 Server 聚焦组的
47 项测试也全部通过。该构建随后在许可检查处失败，因此这次记录不构成完整发行成功的证据。

日志为 `/tmp/javaclaw-package-boundaries-final.log`，SHA-256 为
`245917df7fb3405384af8093e1af3333e931a52ba53f7996f5b214338824a988`。

## 明确修复

许可失败来自新引入的 `org.tukaani:xz:1.12`。已核对[官方发行说明](https://tukaani.org/xz/java.html)、本地该版本
sources JAR 的 SPDX 声明和 binary JAR 的 `Bundle-License: 0BSD`，仅将实际的 `0BSD` 标识加入发行许可白名单。
`failOnMissing` 和 `failOnBlacklist` 均继续为 `true`，未放宽其他许可证规则。

发行镜像的 `legal/xz-java-COPYING.txt` 保存
[官方 v1.12 COPYING](https://github.com/tukaani-project/xz-java/blob/v1.12/COPYING) 原文，`xz-java-NOTICE.txt`
记录实际坐标、版本、用途与上游来源。新的打包测试要求 NOTICE 中的版本与所复制 JAR 的 Manifest 一致。

检查同时发现旧 Knowledge Worker 隔离清单仍从主运行时排除 Commons Compress、Commons IO 和 Commons Lang。
新 `SafeToolchainArchive` 在 App Server 内执行 ZIP/TAR 解包，需要这些依赖；Commons Compress 1.28.0 的发布
POM 也将 Commons IO 2.20.0 和 Commons Lang 3.18.0 声明为普通依赖。现已只移除这三项主镜像排除规则，保留原
PDF/POI 与独立 Worker 的隔离规则。`DistributionRuntimeClasspathTest` 新增断言，检查主发行中的四个实际解包 JAR
及 XZ 许可文件，避免仅在 Maven 测试 classpath 中成功、发行后缺类。

上述两项修复已通过本页开头记录的完整 `clean verify`；不把修复前通过的 87 项测试重复累计为本轮数量。
Windows 的真实签名、SCM、WFP、ACL 与安装/卸载仍需 Windows 原生 Runner 验证，macOS 的条件跳过不算平台通过。
