# 便携应用目录

JavaClaw 发行包使用一个用户可写的外层目录：

```text
JavaClaw/
  runtime/       已签名应用与原生库（运行时只读）
  plugins/       用户安装的插件
  data/          数据库、skills、浏览器状态、截图、日志、缓存和临时文件
```

网页操作优先使用系统已安装的 Google Chrome。系统缺少 Chrome 时，仅从 Google 官方
Chrome for Testing 下载当前系统与 CPU 对应的 Chrome，保存在
`data/cache/google-chrome/<平台>/<版本>/`；后续启动复用完整缓存，不重复下载。
程序不会安装 Firefox、WebKit、ChromeDriver 或额外的 headless shell，也不会更改系统默认浏览器。
下载和解压在 `data/tmp/` 中完成，通过完整性与可执行文件检查后才发布缓存；失败可在下次操作时重试。

启动器从自身代码位置向上查找 `runtime/`，把它的父目录作为应用根。开发运行从包含 `pom.xml` 和 `src/` 的源码树确定根目录；Maven 测试和 `ui-test` 只允许把开发根改到该源码树的 `target/` 内。启动不依赖进程当前工作目录。发行包应将 macOS `.app` 放在外层 `runtime/` 下，并保持其内部只读；Windows 不应把这个便携目录放在不可写的 `Program Files` 下。

启动前会检查应用根可写、数据格式正确，并拒绝受管目录中的符号链接。旧 `javaclaw.data.dir` 只接受精确的 `JavaClaw/data` 路径，不能将应用数据改存到外部。数据格式为 `4`；从格式 `3` 首次升级时，启动器取得应用目录锁和旧实例数据锁后自动清空此应用目录的 `data/` 与 `plugins/`，中断后继续清空。不读取旧工作目录，也不迁移旧数据库或插件。未知格式或不安全路径会阻止清空。详见 [4.0 升级说明](upgrade-4.0.md)。

运行中对截图、技能安装和插件加载的路径也会拒绝已存在的符号链接。检查与第三方库实际写入之间仍可能发生并发目录替换；当前 Java 路径 API 无法把这两步变成一个原子操作。
