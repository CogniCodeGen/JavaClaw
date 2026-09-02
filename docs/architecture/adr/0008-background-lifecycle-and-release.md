# ADR 0008：后台生命周期与发布证据

- 状态：Accepted
- 日期：2026-09-01

## 背景

App Server 需要在客户端退出后继续等待审批或运行 Schedule，同时不能永久残留空闲后台进程。原生 Sandbox、安装与
签名还需要按平台提供独立证据。

## 决策

活动 Turn、等待审批/输入和启用 Schedule 持有 lease；无客户端且无 lease 后固定等待 60 秒退出。首个 Schedule
注册登录启动项，最后一个注销。托盘只控制和显示 Server。发布要求五个原生 Runner、性能/背压、安装启动、SBOM、
许可证、签名和 UI Golden 全部通过。每个发行包内置 CycloneDX SBOM、许可白名单输出和逐文件 SHA-256 清单；外层
产物重新生成哈希清单。正式标签必须匹配非 SNAPSHOT 项目版本，三平台凭据缺失立即失败。签名后的产物同时生成
GitHub provenance 与 SBOM attestation。

## 结果

后台存活原因可查询且可测试。构建产物与其依赖、许可和来源可独立核对。任何缺失平台证据都是发布阻断，不能用
其他平台结果、跳过测试、SNAPSHOT 或未签名产物替代。
