# 桌面原生能力的便携发行

首版发行目标是 macOS 14+ 和 Windows 11。Linux Provider 在此版本返回不可用。
外层 `JavaClaw/` 必须位于用户可写位置，不要安装在 Windows `Program Files`；
macOS `.app` 位于 `runtime/`，其签名内容在运行时保持只读。

```text
JavaClaw/
  runtime/
    JavaClaw.app/                 macOS：包含 JDK 25 和应用依赖
    JavaClaw/                     Windows/Linux：包含 JDK 25 和应用依赖
    native/macos/libjavaclaw_desktop.dylib
    native/windows/javaclaw_desktop.dll
  plugins/
  data/
```

每个平台的发行包只包含该平台对应的应用镜像和原生库。启动 macOS
`runtime/JavaClaw.app` 或 Windows `runtime/JavaClaw/JavaClaw.exe`。
应用从自身 JAR 的代码位置定位外层目录，因此不依赖当前工作目录。
`data/` 保存应用管理的数据库、浏览器资产、截图、日志和临时文件；
`plugins/` 保存可写扩展。操作系统管理的授权记录仍由操作系统保存。
Linux 启动 `runtime/JavaClaw/bin/JavaClaw`；此包可运行主程序与服务插件，
当前版本不提供 Linux 桌面原生能力。

## 装配前置条件

先用 JDK 25 和 Maven 构建主 JAR：

```bash
mvn --batch-mode --no-transfer-progress -DskipTests package
```

装配脚本默认读取 `target/javaclaw-<version>.jar`。macOS 和 Linux 可用
`JAVACLAW_HOST_JAR`、Windows 可用 `-HostJar` 指定其他已完成构建的主 JAR。
如果发行版还包含现有的 Deliverance 插件，须先完成其签名与
`target/distribution/plugins/` 暂存步骤；脚本会把已有的插件目录纳入外层
`plugins/`，不会自行跳过插件签名验证流程。
脚本调用 `mvn dependency:copy-dependencies`，把运行依赖放入应用镜像，
并从 `src/main/native/` 构建该平台 C ABI 库。已有输出目录会导致失败，
避免覆盖用户的 `data/` 和 `plugins/`。

macOS 发行机需要 Xcode Command Line Tools、JDK 25 `jpackage`，以及有效的
Developer ID Application 签名身份：

```bash
export CODESIGN_IDENTITY='Developer ID Application: Example (TEAMID)'
scripts/assemble-portable-macos.sh --output /absolute/path/to/JavaClaw
```

脚本把屏幕采集用途说明写入 `.app/Contents/Info.plist`，随后重新签署
`.app`，再对外置的 `.dylib` 签名，并用 `codesign --verify --strict`
检查两者，拒绝临时的 ad-hoc 签名。
`codesign --timestamp` 需要能访问 Apple 时间戳服务。构建结束后，
`runtime/` 被标记为只读。

本机目录与启动器定位验证可显式使用未签名开发模式：

```bash
scripts/assemble-portable-macos.sh --development-unsigned --offline \
  --output "$PWD/target/portable/JavaClaw-dev"
```

开发模式跳过证书签名与正式验签；修改 `Info.plist` 后，脚本用临时
ad-hoc 签名保持 `.app` 完整性，产物不能作为发行包。正式装配始终要求
`CODESIGN_IDENTITY`，并验证 `.app`、`.dylib` 与内置 Java 的 Developer ID Application 签名。
`--offline` 仅在 Maven 所有运行依赖已缓存时使用。

Windows 发行机需要 JDK 25、Maven、Visual Studio 2022、Windows 11 SDK、
`signtool.exe` 和可用于代码签名的证书。证书指纹可以从签名证书的
`Get-ChildItem Cert:\CurrentUser\My` 输出取得：

```powershell
$env:JAVACLAW_CODESIGN_THUMBPRINT = '<40 个十六进制字符>'
scripts/assemble-portable-windows.ps1 -OutputDirectory 'C:\release\JavaClaw'
```

脚本对 JavaClaw 启动器、`javaclaw_desktop.dll` 和内置 `java.exe` 签名，用
`signtool verify /pa` 与 `Get-AuthenticodeSignature` 校验证书指纹。
其时间戳服务 URL 可通过 `-TimestampUrl` 指定；离线装配需要先准备
可访问的时间戳服务。Windows 的只读文件属性仅防止误改，不能阻止
拥有该目录写权限的用户替换文件。

Linux 可在 JDK 25 和 Maven 环境中装配：

```bash
bash scripts/assemble-portable-linux.sh --output /absolute/path/to/JavaClaw
```

所有平台显式设置 `jlink` 选项，保留服务插件隔离进程使用的 `bin/java`
（Windows 为 `bin/java.exe`），并在装配时使用插件的固定 JVM 参数执行
`-version`；若运行时或必需模块缺失，装配失败。插件不会回退到系统 Java。

## GitHub 发行工作流

`deliverance-release.yml` 从 Maven 的 `project.build.finalName` 读取主工件名，
签署 Deliverance 插件并将签名者固定到主 JAR，再为五个平台目标生成完整便携目录。
macOS 和 Windows 复用上述签名装配脚本，Linux 复用对应的 JDK 镜像装配脚本。
ZIP 包含外层 `JavaClaw/runtime/`、`JavaClaw/plugins/` 与 `JavaClaw/data/`，
macOS 和 Linux 归档保留可执行权限及运行时内部的符号链接。所有目标成功后才发布标签。

在 GitHub 的 `release` 环境中配置原有的四项
`DELIVERANCE_JARSIGNER_*` 插件签名 secrets，并增加平台代码签名 secrets：

| 平台 | Secret | 内容 |
| --- | --- | --- |
| macOS | `JAVACLAW_MACOS_CERTIFICATE_P12_BASE64` | 含私钥的 Developer ID Application PKCS#12 文件的 Base64 |
| macOS | `JAVACLAW_MACOS_CERTIFICATE_PASSWORD` | PKCS#12 密码 |
| macOS | `JAVACLAW_MACOS_CODESIGN_IDENTITY` | 完整的 `Developer ID Application: ...` 身份名称 |
| Windows | `JAVACLAW_WINDOWS_CERTIFICATE_PFX_BASE64` | 含私钥的代码签名 PFX 文件的 Base64 |
| Windows | `JAVACLAW_WINDOWS_CERTIFICATE_PASSWORD` | PFX 密码 |

工作流只在对应平台导入签名证书，完成后清理临时私钥文件、macOS 临时钥匙串和
Windows 导入的签名证书。缺少平台签名证书会阻止该平台归档，不生成开发签名发行物。

## 完整性与验收

发行脚本会拒绝插件与数据目录中的符号链接、Windows reparse point，
只允许 `jpackage` JDK 在 `runtime/` 内部解析的符号链接。应用不从插件目录、
系统缓存或工作目录装载原生库。Java 运行时只按固定的
`ApplicationHome/runtime/native/<platform>/` 绝对路径加载，开发源码树
才可使用 `target/native/`。启动前检查目标目录可写与迁移数据格式，
格式 3 升级会在独占启动锁下清空此应用目录内的旧 `data/` 与 `plugins/`，
具体行为见 [4.0 升级说明](upgrade-4.0.md)。

上述签名检查发生在**发行装配时**。如果安装后的用户可写外层目录被
其他进程篡改，当前 Java 加载器不会在每次运行时重新验证原生库签名或
绑定签名者。Windows 运行时 Authenticode 验证及签名者绑定仍需后续增强；
因此发行物应通过可信渠道分发，并在最终归档前再次核对签名与哈希。

在目标系统实机验收时，还需验证遮挡窗口预览、弹窗切换、多显示器缩放、
最小化与权限撤销、目标重启、前台接管授权、会话关闭及任意工作目录启动。
跨平台原生行为不能由 macOS 上的 Maven 测试代替。
