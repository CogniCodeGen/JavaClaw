JavaClaw 5.0 本地发行包

运行 bin/javaclaw 打开桌面。Desktop 只通过 Java SDK 与本机 App Server 通信，不直接访问 H2、
Provider 或扩展实现。macOS 与 Linux 使用仅当前用户可访问的 Unix Domain Socket。Windows 使用拒绝
远程客户端、仅向 SYSTEM 与当前登录 SID 授权的 Named Pipe。

默认数据库位于 $HOME/.javaclaw/data-v5，日志位于 $HOME/.javaclaw/data-v5/logs。JavaClaw 5 不读取、
迁移或回填其他数据目录。内部服务不监听 TCP 或 WebSocket。

运行 bin/javaclaw-health（Windows 使用 bin\javaclaw-health.cmd）执行安装后检查。检查会创建
独立的临时 data-v5，完成 Protocol v2 初始化和 Workspace 查询，然后只删除该临时目录。

主应用 runtime 与 workers/browser、workers/knowledge、workers/skill 相互隔离。启动器只会把入口、版本标记、
依赖边界和 real path 均校验通过的 Worker image 交给 App Server；缺失或被篡改时对应能力保持不可用，不使用宿主
JDK、PATH 或开发 classpath 回退。Browser image 携带构建时锁定的 Playwright 1.52 Chromium，运行期不会下载；
browser-login-v1.capability 和 browser-oauth-v1.capability 只由对应平台的真实
Native Sandbox/Broker 分项 smoke 成功后生成，二者不互相代替。

legal/LICENSE.txt 是 JavaClaw 的 MIT 许可证。evidence/bom.json 是发行运行时的 CycloneDX 1.6 SBOM，
evidence/THIRD-PARTY.txt 是经过许可白名单校验的运行依赖清单。MANIFEST.json 记录包内每个普通文件和
符号链接的 SHA-256；正式发布产物还带有外层 RELEASE-MANIFEST.json、平台签名以及 GitHub provenance
和 SBOM attestation。任一证据缺失或校验失败都会阻止正式发布。
