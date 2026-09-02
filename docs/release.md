# JavaClaw 5 发布与供应链

本页描述当前发布流程。`5.0.0-SNAPSHOT` 只用于开发构建；正式发布前必须把 Reactor 版本改为与标签一致的非
SNAPSHOT 版本。唯一工作流 `.github/workflows/javaclaw-v5.yml` 会拒绝版本不一致的 `v5.*` 标签。

## 本地产物

运行以下命令会构建当前平台发行包：

```bash
mvn -pl javaclaw-packaging -am clean verify
```

输出分为三个明确范围：

- `javaclaw-packaging/target/distribution`：展开后的应用、主 jlink runtime、三个独立 Worker image、启动脚本和包内
  `MANIFEST.json`。
- `javaclaw-packaging/target/release-evidence`：CycloneDX 1.6 `bom.json` 与 `THIRD-PARTY.txt`。
- `javaclaw-packaging/target/release`：ZIP、原生安装包、签名和外层 `RELEASE-MANIFEST.json`。

CycloneDX 清单只收集发行运行时的 compile/runtime 依赖。许可证门禁拒绝缺失元数据和白名单之外的许可证；新增依赖
时必须审阅其发行义务，不能仅为让构建通过而扩大白名单。两个 SHA-256 清单都按路径排序，覆盖普通文件和符号链接；
清单自身不参与哈希。

发行布局固定为以下四个互不复用的 runtime：

- `runtime/`：Desktop、CLI 与 App Server；不包含 Playwright、Chromium、PDFBox、POI、Knowledge Worker 或
  `jdk.compiler`/`jdk.jshell`。
- `workers/browser/`：最小 Java runtime、Browser Worker/Playwright 依赖和构建时固定的 Chromium 目录。
- `workers/knowledge/`：最小 Java runtime、Knowledge Worker、PDFBox 与 POI 依赖；不包含 Playwright。
- `workers/skill/`：只供已发布 Skill 使用的签名 runtime，明确包含 `java.compiler`、`jdk.compiler` 与
  `jdk.jshell`。

启动器只在 `worker-image-v1.capability`、入口、依赖边界和 real path 全部通过检查后才向 App Server 传递
`*.worker.image-root`。IDEA 直接运行、缺少 image 或 image 被篡改时保持不可用，不从开发 classpath、宿主 JDK 或
PATH 回退。jpackage 的输入目录本身只含主应用库，因此三个 `workers/` 会在生成 app image 后显式复制到平台的
`$APPDIR/workers`，再执行签名与安装包组装。

Browser image 只接受构建输入属性 `javaclaw.playwright.browsers.path` 指向的绝对目录。该目录必须带有内容为
`playwright:1.52.0` 的 `.javaclaw-playwright-version`，并包含 Chromium；Worker 运行期禁止下载。CI 使用锁定的
`playwright@1.52.0 install chromium` 下载到 Runner 临时目录，再交给 Packaging。五个 Runner 会删除旧
`browser-login-v1.capability` 和 `browser-oauth-v1.capability`，用真实 Native Sandbox 启动该 Chromium，分别验证
人工登录的私有 storage state，以及 OAuth 的宿主 Broker 导航、精确 loopback 本地截获和私有 callback。
每项只有全部断言成功后才原子写入当前平台的只读回执；两个回执不互相代替，安装脚本缺少任意一项即失败。

可在签名或传输后重新校验外层目录：

```bash
java -cp javaclaw-packaging/target/classes com.javaclaw.release.ReleaseManifestMain \
  verify javaclaw-packaging/target/release \
  javaclaw-packaging/target/release/RELEASE-MANIFEST.json
```

## 正式标签

五个 Runner 分别覆盖 macOS arm64、macOS x64、Linux arm64、Linux x64 和 Windows x64。每个 Runner 必须先完成
格式、静态规则、全仓测试、覆盖率、性能/背压、原生 Sandbox 和安装后健康检查，才进入签名步骤。

- macOS：签署 app image 和 DMG/PKG，提交 Apple notarization，并 staple 公证结果。
- Linux：生成 DEB，并使用发行 GPG key 创建和验证 detached signature。
- Windows：使用 AppContainer/ConPTY 测试通过的构建生成 MSI，签署内置 launcher 与最终 installer，并使用可信
  timestamp server。
- 所有平台：签名完成后重建外层清单，并分别生成 GitHub build provenance 与 CycloneDX SBOM attestation。

正式流程采用缺失即失败的凭据模型。Repository 或受保护 release environment 需要配置：

- Apple：`JAVACLAW_APPLE_CERTIFICATE_P12_BASE64`、`JAVACLAW_APPLE_CERTIFICATE_PASSWORD`、
  `JAVACLAW_APPLE_SIGN_IDENTITY`、`JAVACLAW_APPLE_INSTALLER_IDENTITY`、`JAVACLAW_APPLE_ID`、
  `JAVACLAW_APPLE_TEAM_ID`、`JAVACLAW_APPLE_APP_PASSWORD`。
- Linux：`JAVACLAW_LINUX_GPG_PRIVATE_KEY_BASE64`、`JAVACLAW_LINUX_GPG_KEY`、
  `JAVACLAW_LINUX_GPG_PASSPHRASE`。
- Windows：`JAVACLAW_WINDOWS_PFX_BASE64`、`JAVACLAW_WINDOWS_PFX_PASSWORD`。

凭据只导入临时 Runner keychain、GPG home 或当前用户证书库，不进入 Maven 配置、日志、发行包或清单。工作流成功
只能证明该次 Runner 结果；当前开发机不能替代 Linux/Windows 原生证据，也不能证明尚未执行的真实签名和公证。

## 当前证据边界

当前源码已配置五个原生 Runner、两类 Browser 原生能力回执和 fail-closed 安装门禁；MCP OAuth、Browser 登录、
Worker image、清单与许可边界已有分模块自动测试。本机仓库还保存 54 张 macOS 设置中心生产 Scene Golden。上述证据
只证明当前分模块与本机行为。

2026-09-02 的本机 macOS aarch64 工作树已通过完整 `mvn clean verify`，15 个 Reactor 模块全部成功；本机 jlink
发行目录也已通过隔离 `data-v5` 的 Protocol v2 健康检查。Linux x64/arm64、macOS x64、Windows x64 的真实
Chromium/Native Sandbox 回执，以及真实平台签名、时间戳和 macOS 公证仍未取得。缺少任一目标 Runner 产物、
外层哈希、签名或 attestation 都会阻止 5.0 发布。

SBOM 由 [CycloneDX Maven Plugin](https://cyclonedx.github.io/cyclonedx-maven-plugin/) 生成；attestation 使用
[GitHub artifact attestations](https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations)。
