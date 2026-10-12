# Deliverance v2 插件

独立 Java 插件公开 MODEL 贡献 `inference`，宿主不依赖 Deliverance 坐标或私有引擎类型。模型格式判断、Hugging Face 量化筛选、参数推荐及 HTTP/TLS 服务均位于插件进程。

先在项目根目录运行 `mvn -pl plugin-runner-java -am install`，准备协议、SDK 和独立 Runner，再执行 `mvn -f sample-plugins/deliverance/pom.xml package`。也可在本示例目录运行 `mvn package`。使用 [SDK 打包 CLI](../../plugin-sdk-java/README.md) 时，根目录相对清单路径为 `sample-plugins/deliverance/src/main/resources/plugin.json`，工件为 `sample-plugins/deliverance/target/plugin.jar`；不读取宿主源码或 `javaclaw-desktop/target/`。

宿主启动需授予清单声明的 `jdk.incubator.vector`、`jdk.httpserver` 模块和原生访问权限。通用 Runner 的 classpath 包含 SDK、协议和传输运行时；Deliverance 和 Jackson 等私有依赖从插件 JAR 加载。

标准操作包含 `list`、`models.sync`、`load`、`unload`、`chat`、`embedding`、`probe`、`catalog.search`、`catalog.detail`、`model.inspect`、`asset.inspect`、`recommend`、`gateway.configure` 与 `logging.configure`。输入输出使用插件内 `protocol/inference-runtime.yaml` 所描述的 JSON 数据，外部 RPC 协议为 v2；JSON 模型契约版本仍为 1.2。

OpenAI 兼容网关默认关闭。`gateway.configure` 接受 `{enabled,configuration,apiKeys,catalog}`，校验 PBKDF2 密钥摘要并执行每密钥权限、模型、RPM、TPM和并发配额。禁止通配监听地址；局域网明文必须显式授权。TLS 使用 PKCS#12 文件及受控 RPC 传入的 `keyStorePassword`，插件不解密宿主的 `encryptedKeyStorePassword`。调用日志只记录批准的元数据。

从项目根运行 `mvn -f sample-plugins/deliverance/pom.xml test`，或从本示例目录运行 `mvn test`，使用离线目录、推荐、协议、预算、SDK 桥接和真实 HTTP 鉴权回归，不下载模型。真实对话、流式与嵌入验收使用 `-Pdeliverance-it verify`，必须提供该 profile 要求的生成与嵌入模型路径及 SHA-256；缺少模型时会明确失败。
