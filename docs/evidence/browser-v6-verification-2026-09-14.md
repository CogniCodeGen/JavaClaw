# V6 浏览器增量验证记录（2026-09-14）

本记录对应初次增量的 18:40 门禁；后续 Site 旧库启动兼容修复及 19:18 完整 `verify` 结果见 [最新验证记录](site-catalog-upgrade-2026-09-14.md)。

环境：macOS 26.5.2 arm64、JDK 25。最终门禁于 18:40 CST 完成。模型测试使用本地模拟服务，未调用付费模型。

## 执行与结果

- `mvn -o spotless:apply`：通过，随后检查实际 diff。
- `git diff --check`：通过。
- `mvn -o spotless:check checkstyle:check`：所有模块通过。
- `mvn -o -fae clean verify`：执行了完整 reactor；Desktop 的新增弹窗样式守卫发现未使用统一 `PlatformDialogs`，已修复并增加真实 JavaFX 回归。
- `mvn -o -fae verify`：上游 13 个模块（含父模块）全部通过；Desktop 的 717 项测试中工作区创建夹具发生一次并发回调竞态。修复为按单 UI 队列顺序执行，不增加原 5 秒超时，不删除业务断言；定向 33 项回归通过。
- `mvn -o -DskipTests install`：只为续跑准备同一源码的本机 Maven 模块产物，不计作测试通过证据。
- `mvn -o -fae verify -rf :javaclaw-desktop`：Desktop 与 Packaging 全部门禁通过，`BUILD SUCCESS`。未使用测试筛选或跳过测试选项。

因此本记录的完整结果来自上游全量 `verify` 与修复后的 Desktop/Packaging 全量续跑；不把之前中断的单次 reactor 记作成功。最后一轮代码修复只涉及工作区创建测试夹具，已通过最终格式和静态检查。

## 最后一次全量模块结果

不重复累计定向回归、重跑或产物准备步骤中的测试。共 3,247 项，3,221 通过，0 失败，0 错误，26 跳过。

| 模块或门禁 | 总数 | 通过 | 跳过 |
| --- | ---: | ---: | ---: |
| Immutable API | 106 | 106 | 0 |
| Extension SPI | 68 | 68 | 0 |
| App Protocol | 157 | 157 | 0 |
| Agent Runtime | 52 | 52 | 0 |
| Model Adapters | 67 | 67 | 0 |
| Built-in Contracts | 94 | 94 | 0 |
| Built-in Extensions | 221 | 221 | 0 |
| Native Hosts | 230 | 213 | 17 |
| Browser Worker | 85 | 85 | 0 |
| App Server | 1,214 | 1,208 | 6 |
| Knowledge Worker | 9 | 9 | 0 |
| SDK / CLI | 128 | 128 | 0 |
| Desktop | 717 | 717 | 0 |
| 独立 JVM 的管理中心 Golden | 1 | 1 | 0 |
| Packaging | 98 | 95 | 3 |

各模块原有覆盖率门槛、依赖声明检查、架构边界和 Packaging 发行清单校验均通过。没有修改覆盖率阈值、扩大 suppression 或重写历史 migration。

## 跳过项和验证边界

- Native Hosts 的 17 项：16 项限 Windows/Linux 的原生验证，1 项需显式启用的系统凭据设施验证。
- App Server 的 6 项：本地真实 Provider、已发行工具链归档、联网依赖准备等需要额外输入或显式环境开关的验收。
- Packaging 的 3 项：1 项 Windows 进程监督，2 项需真实原生浏览器镜像和显式启用的 Browser smoke。

macOS 私有 bootstrap 探针返回 125，未进入真实可见 Chromium 的端到端运行；当前三平台的交互进程树证明门禁仍拒绝发布。上述 Browser smoke 的跳过不算浏览器能力验收通过。Linux、Windows 未在对应平台运行。详见 [原生前置验证](browser-interactive-native-2026-09-14.md)。

截图使用生产 JavaFX 控件与测试数据，展示账号、来源授权和人工接管入口；不是真实网站登录或 Chromium 窗口成功的证据。已登录或人工接管过的会话仍禁止截图进入模型，详见 [功能与限制](../browser-v6.md)。

完整 Desktop 测试日志中仍有旧“更多”菜单弹层的 CSS token lookup 警告。相关面板、基础样式和测试未在本轮改动；新增浏览器账号、授权与弹窗测试段未出现这些警告。现有 Golden 检查通过。

## 本机原始日志

- `/private/tmp/javaclaw-browser-delivery-format.log`
- `/private/tmp/javaclaw-browser-delivery-static.log`
- `/private/tmp/javaclaw-browser-clean-verify-first.log`
- `/private/tmp/javaclaw-browser-final-verify.log`
- `/private/tmp/javaclaw-workspace-creation-regression.log`
- `/private/tmp/javaclaw-browser-resume-artifacts.log`
- `/private/tmp/javaclaw-browser-desktop-packaging-verify.log`

这些日志位于本机临时目录，不是仓库长期保存的发布证据；本文件保留命令、实际结果和未验证范围。
