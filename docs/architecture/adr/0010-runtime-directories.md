# ADR 0010：程序目录与 data-v6 运行目录

- 状态：Accepted
- 日期：2026-09-07
- 关联：ADR 0009；取代 ADR 0005 的历史数据根选择

## 背景

独立打包、IDEA、健康检查和后台启动必须使用一致且可解释的数据根。隐式 HOME 回退和旧版本目录探测会混用状态，
也会让只读安装位置的错误隐藏为“启动成功但数据消失”。

## 决策

`LocalRuntimeDirectories.programDirectory()` 使用显式 `javaclaw.program.dir`，开发环境缺省使用 `user.dir`。
`dataDirectory()` 优先使用非空 `javaclaw.data.root`，否则返回程序目录下 `data-v6/`。路径返回规范绝对值，
解析器本身不创建目录。发行启动器必须传递程序目录，IDEA 使用 `.javaclaw/idea/data-v6` 作为显式隔离开发根。

App Server 打开数据库前检查所有者、目录访问权限、符号链接与实际写入能力。POSIX 数据目录限当前用户访问；
非 POSIX 文件系统必须提供 ACL：新目录以 owner-only ACL 创建并回读，已有目录拒绝非所有者、系统查询确认的本地
SYSTEM/Administrators 主体拥有写入、删除或 ACL/owner 管理权限。未知主体不按名称猜测，无法安全配置时启动失败。
Windows 的真实继承与权限行为仍由目标 Runner 验收。不可写或不安全时明确失败，不能悄悄移动到 HOME。
日志可通过 `javaclaw.log.dir` 显式指定到同一数据根的 logs；日志错误不构成访问旧目录的授权。

所有入口都不得搜索、读取、导入、迁移、修改或删除 data-v5。用户明确指定新数据根不启用历史数据兼容。
此目录约定不授予模型访问安装目录、HOME 或其他 Workspace 的能力，文件权限仍由当次执行安全链决定。

## 结果与验收

开发、发行、后台与健康检查的数据位置可预期；只读安装目录需要显式指定安全可写根。发布前必须覆盖新目录初始化、
显式覆盖、只读根、错误所有者、symlink、日志和 launcher 路径，且旧目录 sentinel 内容与元数据保持不变。
三平台原生权限与安装验收的真实状态见[验收矩阵](../acceptance-matrix.md)。
