# 便携应用目录

JavaClaw 发行包使用一个用户可写的外层目录：

```text
JavaClaw/
  runtime/       已签名应用与原生库（运行时只读）
  plugins-v2/    全局不可变插件工件与恢复记录
  data-v5/       数据库、工作区插件绑定、配置、skills、浏览器状态与缓存
```

网页操作优先使用系统已安装的 Google Chrome。系统缺少 Chrome 时，仅从 Google 官方
Chrome for Testing 下载当前系统与 CPU 对应的 Chrome，保存在
`data-v5/cache/google-chrome/<平台>/<版本>/`；后续启动复用完整缓存，不重复下载。
程序不会安装 Firefox、WebKit、ChromeDriver 或额外的 headless shell，也不会更改系统默认浏览器。
下载和解压在 `data-v5/tmp/` 中完成，通过完整性与可执行文件检查后才发布缓存；失败可在下次操作时重试。

启动器从自身代码位置向上查找 `runtime/`，把它的父目录作为应用根。开发运行优先识别同时包含根 `pom.xml`、`javaclaw-desktop/pom.xml` 与 `javaclaw-desktop/src/` 的聚合仓库根，因此从根目录或 Desktop 模块启动都继续使用仓库根的 `data-v5/` 与 `plugins-v2/`。宿主构建与测试输出位于 `javaclaw-desktop/target/`，Maven 测试和 `ui-test` 使用其中的隔离应用根。启动不依赖进程当前工作目录。发行包应将 macOS `.app` 放在外层 `runtime/` 下，并保持其内部只读；Windows 不应把这个便携目录放在不可写的 `Program Files` 下。

启动前会检查应用根可写、数据格式正确，并拒绝受管目录中的符号链接。`javaclaw.data.dir` 只接受精确的 `JavaClaw/data-v5` 路径。当前数据格式为 `5`；新目录独立初始化，旧 `data/`、`plugins/` 及旧升级标记保持原样。详见 [4.0 升级说明](upgrade-4.0.md)。

`runtime/desktop.jar` 与 `runtime/lib/` 组成 Desktop classpath。`runtime/plugin-runner.jar` 仅作为子进程入口，不加入 Desktop classpath。可选发行插件列于 `runtime/plugin-distribution.json`，从 `runtime/plugin-packages/` 经同一静态校验流程登记，初始停用。

运行中对截图、技能安装和插件加载的路径也会拒绝已存在的符号链接。检查与第三方库实际写入之间仍可能发生并发目录替换；当前 Java 路径 API 无法把这两步变成一个原子操作。
