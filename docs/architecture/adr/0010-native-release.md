# ADR-0010：同版本三平台原生发行

- 状态：Accepted
- 日期：2026-08-27

## 决策

`javaclaw-packaging` 用 jlink 生成最小运行时，用 group-prefixed 依赖名避免相同 artifactId
覆盖，再用 jpackage 生成 macOS arm64/x64 DMG/PKG、Linux arm64/x64 DEB/RPM、Windows x64
MSI/EXE。App Server、Launcher、Browser Service 与 Windows Host 作为内部组件分发。

每个发行物包含 CycloneDX SBOM、THIRD-PARTY 许可清单、SHA-256 与启动健康检查。PR 可构建未签名
包；正式 Release 必须完成 Apple 签名/公证、Linux GPG、Windows Authenticode/时间戳。五个原生
Runner 对同一版本全部成功后才能创建 Release。

## 结果

缺少任一签名凭据或平台安全测试失败都阻止完整 Release，不发布部分平台或无沙箱降级包。
