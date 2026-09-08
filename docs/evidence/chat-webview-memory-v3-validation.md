# 聊天 WebView、文档与记忆 V3 验证记录

日期：2026-09-08；状态：首轮构建及指定回归记录；其他平台发行验收待执行。

本文保留首轮计数与证据范围。追加的真实功能闭环、生产 SDK / UI 重放、问题修复及最终全量结果见
[功能与 UI 对比验收](chat-webview-memory-v3-functional-ui-validation.md)。

## 范围

实现范围为 [V3 计划](../proposals/chat-webview-memory-v3.md)。保留 14 个模块、Protocol v3、data-v6、
App Server 唯一组合根、Desktop SDK 边界与 `509f197` 设计体系；新增数据库 V005–V007 前向迁移。
未修改历史 migration、Golden 截图夹具/比较规则、覆盖率门槛或个人全局配置。
JavaFX 26 造成的滚动条末端参考像素变化经过[单独审阅](chat-webview-memory-v3-golden-review.md)，有明确基线更新记录。

## 完整构建

2026-09-08 00:51:46 CST 完成，耗时14分37秒，15个Reactor project全部 `SUCCESS`：

```bash
mvn -s /private/tmp/javaclaw-webview-maven-settings.xml spotless:apply clean verify spotless:check checkstyle:check
```

临时 settings 仅将依赖镜像指向官方 Maven Central，以解决本机缓存的仓库身份差异；没有修改个人或全局配置。
精确内容、完整日志路径与摘要见[机器记录](chat-webview-memory-v3-validation.json)。

共2505项测试记录，2480项通过、25项条件跳过，0失败、0错误。Desktop为316项单测和1项覆盖54图的Golden。
覆盖率、依赖声明/收敛、架构边界/生产包无环、Spotless、Checkstyle、Golden、许可证、SBOM、发行清单和ZIP生成
全部通过原有门禁；`git diff --check`通过。定向回归不重复计入本次总数。

| 模块 | 测试记录 | 条件跳过 | 行覆盖率 | 分支覆盖率 |
| --- | ---: | ---: | ---: | ---: |
| api | 105 | 0 | 93.47% | 82.37% |
| extension-spi | 66 | 0 | 95.10% | 83.51% |
| protocol | 149 | 0 | 91.49% | 82.42% |
| agent-runtime | 42 | 0 | 93.37% | 80.70% |
| model-adapters | 63 | 0 | 88.76% | 73.87% |
| builtin-contracts | 67 | 0 | 83.30% | 73.85% |
| builtin-extensions | 201 | 0 | 90.58% | 71.48% |
| native-hosts | 201 | 17 | 82.36% | 70.87% |
| browser-service | 47 | 0 | 81.67% | 71.10% |
| app-server | 1029 | 6 | 91.39% | 80.41% |
| knowledge-worker | 7 | 0 | 92.59% | 76.67% |
| client | 122 | 0 | 87.39% | 71.57% |
| desktop | 317 | 0 | 87.22% | 70.81% |
| packaging | 89 | 2 | 82.63% | 76.67% |

跳过项包括其他OS的原生测试、系统凭据前置、真实Provider和需要显式归档/网络配置的Coding及Browser验收。
逐项名称和原因在机器记录中保留，不能计为已执行通过。9份既有V001–V004 SQL与基线字节一致；Protocol仍为v3，
方法目录172项，data-v6与14模块保持。

## 实现与回归边界

- 流事件与消息在真实 H2 中持久化；非法 Unicode 导致事务回滚；订阅幂等、水位、实时唤醒和慢连接关闭有测试。
- 大于 RPC 单帧预算的历史消息通过摘要和完整文档引用展示；SDK 验证通知空窗、断线恢复、Unicode 尾部和队列限额。
- 文档预览验证连接及工作空间归属、不可变文件版本、当前权限撤销、附件所有权、相对资源与缓存预留。
- macOS 原生 Worker 实际执行工作区只读快照；没有使用普通服务端文件 IO 绕过该边界。
- 记忆有效性、冲突裁决、绑定删除不复活、学习单次调用和未知批次恢复有固定模型测试；没有调用付费模型。
- 真实 JavaFX/WebKit 测试发现 Canvas 路径异常，应用适配层已修复并定向复测；旧客户端 Graph 元数据兼容单独验证。
- 三个 WebView 宿主均保留强引用桥、代次、ready/绘制确认、超时重建及原生简版；正常页面也可右键主动切换。
- 预览关闭屏障覆盖来源读取、权限核验和缓存创建；单个删除失败仍继续取消其他句柄，并等待已入场操作完成。
- 历史文件引用只接受顶层成功的权威工具结果；输出内嵌套成功字段和伪造成功文本不能覆盖实际失败状态。
- 不支持的文档格式保留已验证名称、大小和原因，同时释放句柄且不读取正文；撤权仍清除旧内容。
- 三个平台原生安装器在签名及最终封装之前复制完整 legal/evidence，缺少输入目录时终止。
- 最终架构门禁发现并修复 `view → web → view` 循环：聊天专用 `ChatSurface` 归入 view，共用 WebView
  宿主保留在 web，形成单向依赖；未以全限定名、排除项或修改无环检查规则掩盖依赖。

上述定向修复均已纳入最终完整 `clean verify`；最后一轮不复用增量覆盖率数据。

## 真实窗口重放

维护入口为 `scripts/replay-desktop-webview.py`，在完成 Desktop 测试后使用其准确 classpath 启动真实
Stage/WebKit。测试使用 10,000 条历史夹具，首屏 100 条、缓存窗口 500 条，持续输出 120 次、目标间隔 50ms。
标记必须同时出现在 DOM 视口中并取得非纯色 snapshot，不能仅凭桥接 ACK 宣称已经显示。

本机记录为 2026-09-07 23:27 CST，macOS 26.5.2 arm64、JDK 25、JavaFX 26.0.2：

| 指标 | 实测 |
| --- | ---: |
| 首次 snapshot | 1504.26ms |
| 展示入口接收至 snapshot P50 / P95 | 42.08 / 67.57ms |
| FX 队列往返 P95 | 15.47ms |
| 滚动命令至 snapshot P95 | 94.29ms |
| 可见流样本 / 发送更新 | 119 / 120 |
| 进程 CPU（100% 为单核） | 91.48% |
| 进程 RSS | 894,912KiB |

原始[指标](chat-webview-memory-v3/desktop-replay/results.json)、[首屏](chat-webview-memory-v3/desktop-replay/first.png)
与[末帧](chat-webview-memory-v3/desktop-replay/stream.png)保留供复核。绘制允许合并更新，最终第119号标记已显示。
接收点为 `ChatSurface.show`，不包含 SDK、网络或 App Server 延迟；snapshot 也不是 OS 屏幕光学可见时刻。
RSS 包含 JVM、WebKit、夹具和高频采样，不是产品稳态内存。单次展示层样本达到250ms目标，不能代替全链路验收。
尚无旧 JavaFX 聊天实现的同场景对照，因此不声明性能提升。

## 最终发行产物运行检查

本机ZIP为 `javaclaw-packaging/target/release/javaclaw-6.0.0-SNAPSHOT-mac-aarch64.zip`，485,224,333字节。
SHA256为 `f469aa80cc98786e0423c61e316bd13b0cb98f62d0352c299531872f93877df4`。
运行时、两个隔离Worker、静态Web资源、许可证、主SBOM与Web补充SBOM均纳入发行清单；产物摘要见机器记录。

最终产物的 `bin/javaclaw-health` 退出码0，在独立临时data-v6上实际启动服务端并完成SDK握手；未读取用户现有数据。
随后直接使用包内runtime与生产lib运行以下真实窗口检查，退出码0：

```bash
javaclaw-packaging/target/distribution/runtime/bin/java --enable-native-access=ALL-UNNAMED \
  -cp 'javaclaw-packaging/target/distribution/lib/*:javaclaw-desktop/target/test-classes' \
  com.javaclaw.desktop.web.WebSurfaceReplayBenchmark /private/tmp/javaclaw-v3-packaged-final
```

额外test-classes仅提供重放入口与FX夹具，不借用系统JDK、Maven生产依赖或生产target/classes。
2026-09-08 00:52:33 CST采样：首屏1798.69ms，接收至snapshot P95为65.81ms，FX往返P95为15.27ms，
滚动P95为90.36ms，120次更新取得120个可见样本，末尾第119号标记已显示。进程CPU为单核基准79.65%，
RSS为935,568KiB；测量范围与前述限制相同。
最终包的[原始指标](chat-webview-memory-v3/packaged-replay/results.json)、[首屏](chat-webview-memory-v3/packaged-replay/first.png)
和[末帧](chat-webview-memory-v3/packaged-replay/stream.png)已保留并检查。

这证明当前macOS发行目录能启动服务端与WebKit，不等于DMG/PKG安装、签名、公证、系统登录启动或其他Runner验收。

## 平台与产品限制

- 当前只有 macOS arm64 本机证据，尚无其他四个 Runner 的本轮回执。
- 尚未取得 Windows/Linux 输入法、字体、DPI、焦点、登录启动、安装和签名验收。
- WebView 页面恢复不能保证进程级原生崩溃恢复。
- 本机 WebKit 彩色 emoji 存在字形异常；JavaFX 25/26 对照都可复现，原生 Label 正常。消息数据和 Unicode
  码点未损坏，可右键切到简版。该像素问题尚未修复，不能将现有测试通过解释为字体全部通过。
- 支持 Markdown、代码、纯文本及 PNG/JPEG/GIF 首帧；PDF、Office 等仅显示元信息和不支持原因。
- 后台任务要求 App Server 保持运行；本轮没有执行五平台登录启动、安装或签名验收。
- 没有调用付费模型；记忆学习的状态与权限回归使用固定模型，尚未评估真实模型的提取质量。
- 历史 Coding 验收中的 macOS Gradle 及 Windows 原子替换限制不因本轮展示改造而消失。
