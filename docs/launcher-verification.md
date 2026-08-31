# 统一启动入口验收记录

本文保留 2026-08-28 的历史结果。最新完整验收见
[2026-08-29 升级验证记录](architecture/upgrade-verification.md)：当前默认 `mvn verify` 执行 323 项、无失败，
性能门禁与真实桌面 smoke 两项需显式开关；
开发布局、发行目录以及只读 DMG 中的 `.app` 均完成真实桌面对话、十二个页面和正常退出验证。
下文较早的测试数量和 SIGTERM 清理限制不代表当前状态。

验证日期：2026-08-28。环境：macOS arm64、JDK 25、Maven 3.9.0。

## 基线与变更范围

实施前重新检查了工作树和构建基线。此前提及的 168 条 Checkstyle 问题在本次基线中已不存在，
Spotless 与 Checkstyle 均通过；本次没有重复整改其他任务的代码，也没有关闭检查规则。

启动器位于现有 `javaclaw-packaging` 模块，Reactor 仍为九模块。Desktop 的生产依赖仍只有
SDK 与 JavaFX；服务端和原生辅助进程继续通过原有 SDK Supervisor / Sandbox Launcher 管理。

## 实际执行结果

| 范围 | 结果 |
|---|---|
| 格式与静态检查 | `mvn spotless:apply`、`mvn spotless:check checkstyle:check` 通过；检查了实际变更范围 |
| 完整 verify | 189 项测试，188 项通过；1 项 Windows AppContainer 专用测试在 macOS 上跳过 |
| 架构与安全 | 九模块、Desktop 依赖和进程创建边界测试通过；macOS 原生沙箱与协议性能门禁通过 |
| IDE 运行布局 | 用各模块 `target/classes` 与 Maven 依赖启动真实桌面，完成握手并加载 Profile；正常退出后所属进程全部结束 |
| 无凭据启动 | 使用隔离的空数据目录，Provider 均为未配置状态，窗口正常显示 `Connected`；没有调用云模型 |
| 全新源码启动 | 无任何 `target` 的源码副本放在含中文和空格的路径，从其他工作目录执行真实 `run.sh`，完成构建并打开桌面 |
| 发行目录 | 使用发行目录自带的 Java 与 `lib/*` 启动真实桌面；窗口、握手和正常退出清理通过 |
| macOS 应用镜像 | `jpackage --type app-image` 成功；实际打开镜像中的应用，确认 `Connected`；发送 SIGTERM 后应用及 App Server 均退出 |
| 发行完整性 | 在线 distribution verify 通过，包含 Launcher JAR、ZIP、SBOM、许可证与校验和；`javaclaw-health` 握手通过 |
| 显式配置兼容 | 命令、classpath、基础设施参数优先级和外部端点免本地依赖的测试通过；现有 SDK UDS 集成测试通过 |
| 失败处理 | 缺少/重复 Native Host 模块、远程 manifest classpath、无发行物、构建失败不启动旧产物、非空未标记数据目录保留等测试通过 |

IDE 布局由自动测试直接使用模块编译输出验证，未在 IntelliJ 界面中点击运行按钮。
macOS 应用镜像的窗口关闭操作因 UI 工具连接中断未完成，故该项记录为 SIGTERM 清理；
正常关闭 JavaFX 窗口后的清理由 IDE 布局和发行目录的真实桌面测试验证。

## 可重复执行的命令

以下桌面测试需要图形会话，会打开并自动关闭测试窗口，数据与缓存均位于独立临时目录。

```bash
mvn spotless:apply
mvn spotless:check checkstyle:check

# 完整测试，包括真实桌面、当前平台原生沙箱和性能门禁
mvn -o -Djavaclaw.require.native.sandbox=true \
  -Djavaclaw.performance.gate=true -Djavaclaw.desktop.smoke=true \
  -Djavaclaw.distribution verify

# 离线模式不能生成 CycloneDX SBOM，因此另外在线完成发行完整性检查
mvn -DskipTests -Djavaclaw.distribution -pl javaclaw-packaging -am verify
javaclaw-packaging/target/distribution/bin/javaclaw-health

# 单独重复 IDE 编译输出的真实桌面测试
mvn -o -pl javaclaw-packaging -am -Dtest=DesktopLaunchSmokeTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Djavaclaw.desktop.smoke=true test
```

`DesktopLaunchSmokeTest` 还支持 `javaclaw.smoke.java` 和 `javaclaw.smoke.classpath`，
可分别指向发行物的 `runtime/bin/java` 与 `lib/*`；含空格的属性参数应作为整体加引号。
`javaclaw.smoke.screenshot` 可指定窗口截图文件，未设置时不保存截图。

## 平台与限制

| 平台 | 本次实际覆盖 | 未验证 |
|---|---|---|
| macOS arm64 | 模块编译输出、全新源码脚本、发行运行时、应用镜像、无凭据启动、后台清理 | x64、DMG/PKG 安装、正式签名与公证、IDE 界面点击运行 |
| Linux | 共用启动解析逻辑与布局测试 | Linux 主机上的脚本、JavaFX、bubblewrap、DEB/RPM |
| Windows | Windows 命令生成与显式配置的跨平台单元测试 | Windows 主机上的 `run.cmd`、JavaFX、Named Pipe/AppContainer、MSI/EXE |

当前 JDK 25 / JavaFX 的 classpath 启动会输出 unnamed module 与 native access 警告，
以及现有 SLF4J 无 provider 提示；本次实际启动未出现 JavaFX 模块缺失错误。没有为消除警告
扩大 Native Host 的模块集合或修改全局 JVM 配置。

本次没有进行模型调用、安装浏览器、修改个人全局配置、迁移旧数据或提交工作树。
