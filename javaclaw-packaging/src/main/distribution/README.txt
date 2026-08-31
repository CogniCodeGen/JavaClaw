JavaClaw 4.0 local distribution

运行 bin/javaclaw（Windows 使用 bin\javaclaw.cmd）打开桌面；安装包中的 JavaClaw 图标使用相同入口。
普通 Java 主类 com.javaclaw.launcher.JavaClawLauncher 负责定位本包依赖，无需手动设置 JavaFX 模块路径。
App Server 由 SDK 自动管理，关闭桌面时清理所属进程；显式连接的外部服务不会被桌面停止。
默认数据、配置、日志和缓存保存在程序目录下的 .javaclaw 子目录。
可用 JAVACLAW_PROGRAM_DIR 整体调整程序本地根，也可用 JAVACLAW_DATA_DIR、
JAVACLAW_CONFIG_DIR 和 JAVACLAW_CACHE_DIR 分别覆盖。
程序或安装目录必须允许当前用户写入；只读目录会明确启动失败，不会回退到其他目录。

The Desktop and CLI communicate only through the Java SDK and local App Server.
Internal services do not expose TCP or WebSocket listeners. A v4 data root is required;
JavaClaw never reads, migrates, or deletes a 3.x data root.
The default v4 storage stays under <program>/.javaclaw and never falls back to the user home directory.

Run bin/javaclaw-health (bin\javaclaw-health.cmd on Windows) after installation.
It creates an isolated temporary v4 root, performs the JSON-RPC initialize/capability
handshake through the packaged SDK/App Server path, and removes only that temporary root.
