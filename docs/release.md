# JavaClaw 6 发布与供应链

本页描述当前发布流程。`6.0.0-SNAPSHOT` 只用于开发构建；正式发布前必须把 Reactor 版本改为与标签一致的非
SNAPSHOT 版本。唯一工作流 `.github/workflows/javaclaw-v6.yml` 会拒绝版本不一致的 `v6.*` 标签。

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

## 运行目录与升级

程序默认使用安装目录下的 `data-v6/`，显式 `javaclaw.data.root` 优先，目标必须以 `data-v6` 结尾。只读安装位置需要用户明确指定可写数据根，
启动器不得静默回退到 HOME。App Server 打开数据库前检查目录所有者、权限、符号链接和实际写入能力；错误必须明确。
升级不会读取、迁移、修改或清理旧 `data-v5`。旧 Profile 和 Protocol v2 不能进入 v6，需使用新的 Role 和独立执行配置。

## 当前证据边界

2026-09-07 的 macOS aarch64、JDK 25 完整 `clean verify` 通过：15 个构建项目、1899 项测试，0 失败、
0 错误、8 项条件跳过；[构建证据](evidence/v6-build-validation.md)记录命令、覆盖率与跳过范围。macOS 本机 jlink/Worker
镜像、发行 ZIP、SBOM、许可和双层哈希生成与校验通过。CI 的五个原生 Runner、真实 Browser 能力与安装门禁仍须取得对应结果。
本机 54 张 macOS 设置中心壳/外观页参考 Scene 已接纳经审阅的 Agent 导航文案更新；真实 JavaFX 产物逐图对照确认
仅左侧标题/描述变化，框外像素完全一致。[差异记录](evidence/v6-settings-golden-review.md)保留前后哈希，逐字节比较
门禁未变，最终独立 JVM Golden 已在完整门禁通过。Agent Studio 正文、新执行选择器及其余页面多状态需补充人工视觉验收。

本机门禁通过不代表跨平台发布就绪。安装后健康检查、三平台原生 Runner、真实 Chromium/Sandbox 回执、系统凭据、
平台签名、时间戳、macOS 公证与 attestation 未完成前阻止发布。

本机[配置与 Prompt 准备基准](evidence/v6-turn-preparation-performance.md)测得 v6 p50/p95 约 160/175 ms、
Git v5 对照约 503/608 ms，所测准备路径满足不超过 10% 的增量目标。最终候选仅一个 JVM、100 个样本；该计时
不包含 Turn 最终写入、Harness、模型完成、UI 或 Sandbox，不是完整 Turn 端到端或跨平台验收。
也未通过付费模型或效果对照实验验收 Role 文本的质量提升。详见[验收矩阵](architecture/acceptance-matrix.md)。

SBOM 由 [CycloneDX Maven Plugin](https://cyclonedx.github.io/cyclonedx-maven-plugin/) 生成；attestation 使用
[GitHub artifact attestations](https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations)。
