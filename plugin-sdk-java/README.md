# Java 插件 SDK v2

插件只依赖 `com.javaclaw:plugin-sdk-java:4.0.0-SNAPSHOT`，使用 `provided` scope。实现 `com.javaclaw.plugin.sdk.Plugin` 并提供公开无参数构造器，入口在 `plugin.json` 的 `entrypoint` 声明。插件 JAR 和私有依赖不进入 Desktop classpath。

`PluginContext` 提供配置 JSON、当前不可变实例身份、私有数据目录、可取消的执行器和受限 `HostClient`。同一实例的工作区、工件哈希、配置 revision、instanceId 始终固定。宿主回调必须使用当前身份，携带截止时间；Agent Run 中发起的回调继续使用原 `PluginCall.request().context`。后台服务须由宿主单独登记，并按插件已授权权限校验。

能力对应关系：

| 声明能力 | SDK 入口 | RPC |
| --- | --- | --- |
| TOOL | `execute` | ToolService |
| MODEL | `execute` | InferenceService |
| SKILL、CONTEXT | `retrieve`，通过 `PluginCall.kind()` 区分 | ContextService |
| WORKFLOW | `execute`，输入、输出均为 JSON 数据 | WorkflowService |
| HOOK | `hook` | HookService |

`PluginEvents.data` 产生 DATA 事件；正常返回后 Runner 产生唯一 COMPLETE。业务最终结果也使用 DATA。宿主不得重放同一请求；异常只返回稳定错误，不泄露插件异常中的配置秘密。插件应定期调用 `Cancellation.throwIfCancelled()`，流式发送受 gRPC 背压限制。钩子允许返回的权限必须是输入权限的子集，预算只能减少。

`PluginContext.executor` 的独立后台任务在执行前向宿主申请可信后台租约；子任务共享租约引用，最后一个任务结束后归还。RPC 派生任务继承原运行身份，不能把任意字符串当作宿主 Run。后台租约最多 30 分钟且不续期，截止时会中断根任务及子任务，并收紧宿主回调的截止时间。请通过 SDK 执行器启动网络服务的请求处理与后台工作。

宿主开始排空时，Runner 调用一次 `Plugin.beginDrain()`。插件应停止接受新连接或事件，保留活跃请求。Runner 分别统计 RPC 和 SDK 后台工作，`Drain(0)` 立即返回状态；既有租约调用及其必要子任务可继续完成，所有工作归零后宿主才正常关闭进程。强制退出会取消剩余工作。

## 构建和打包

在项目根目录构建公共模块和 Runner：

```sh
mvn -pl plugin-runner-java -am install
mvn -f sample-plugins/hello/pom.xml package
```

Runner fat JAR 同时包含通用打包 CLI。以下命令创建可在插件中心预览的未签名本地包；安装后默认停用，启用前由用户审查权限：

```sh
java -cp plugin-runner-java/target/plugin-runner-java-4.0.0-SNAPSHOT-all.jar \
  com.javaclaw.plugin.sdk.packaging.PluginPackageMain \
  --manifest sample-plugins/hello/plugin.json \
  --artifact sample-plugins/hello/target/plugin.jar \
  --output sample-plugins/hello/target/hello-2.0.0.zip
```

附加私有依赖使用重复的 `--file lib/example.jar=/absolute/path/example.jar`。包中每个普通文件都进入规范化的 `files.sha256`，不允许路径穿越或占用保留清单路径。

发布签名包时增加 `--private-key /path/publisher-private.pem --public-key /path/publisher-public.pem`。私钥格式为 PKCS#8、公钥格式为 X.509，支持 DER 和 PEM，只支持 Ed25519。CLI 验证公私钥匹配，并对 `files.sha256` 原始字节签名；私钥不会进入 ZIP 或日志。

## Runner 启动契约

启动命令仅为 `java -jar plugin-runner.jar`。双向不同 bearer token 与身份、工件路径等通过受控环境传递；禁止把 token 置于命令行。Runner 首先只启动回环服务并输出一行：

```text
JAVACLAW_PLUGIN_V2_READY {"port":12345,"protocolMajor":2}
```

此时未加载插件实现。宿主用 token 和完整身份调用 Initialize，声明和工件文件哈希校验通过后才加载。后续插件 stdout 转入 stderr，避免污染启动通道。运行时使用子优先私有类加载器以及对应的资源/SPI 上下文；仅 JDK、SDK 和生成的协议类型共享。

必需环境项为 `JAVACLAW_PLUGIN_HOST_PORT`、`JAVACLAW_PLUGIN_TOKEN`、`JAVACLAW_HOST_TOKEN`、`JAVACLAW_PLUGIN_ID`、`JAVACLAW_PLUGIN_HASH`、`JAVACLAW_PLUGIN_REVISION`、`JAVACLAW_WORKSPACE_ID`、`JAVACLAW_PLUGIN_INSTANCE_ID`、`JAVACLAW_PLUGIN_ARTIFACT`、`JAVACLAW_PLUGIN_ARTIFACT_FILE_HASH`、`JAVACLAW_PLUGIN_MAIN`、`JAVACLAW_PLUGIN_DATA_DIRECTORY`。宿主应同时传入 `JAVACLAW_PLUGIN_PARENT_PID`，父进程退出时 Runner 自动关闭。

插件可在签名清单 `runtime.javaModules` 声明宿主允许的模块，在 `runtime.nativeAccess` 声明原生访问要求。它们需要对应权限 `runtime.module:<module>` 和 `runtime.native-access`，不能注入任意 JVM 参数。
