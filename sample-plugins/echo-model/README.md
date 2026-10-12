# Echo MODEL 协议验证样例

这是确定性协议夹具：对话返回前缀和最后一条用户消息，嵌入返回文本 SHA-256 派生向量。它不加载模型权重，也不执行真实语言模型推理。计量单位为 Unicode 字符，便于准确断言结果与取消行为。

插件只有标准 `MODEL / inference` 贡献，通过独立 Runner 支持 `list/load/unload/probe/chat/embedding/model.inspect/asset.inspect/recommend/catalog.search/catalog.detail/models.sync`。在线目录条目标记为受限，需从本地导入 `fixtures/minimal` 目录。模型格式是 `echo-model.json`，无需 `config.json` 或 Safetensors；宿主模型管理页按声明式 UI Schema 使用通用模型页面。此样例不提供公开 HTTP 服务，启用公开接口会明确失败。

先在项目根构建并安装 SDK，再构建 Runner，最后独立验证样例：

```bash
mvn -pl plugin-runner-java -am install -DskipTests
mvn -f sample-plugins/echo-model/pom.xml verify
```

SDK 与 Runner 准备完成后，也可在本示例目录运行 `mvn verify`；插件仍使用自身 `target/` 与独立 Runner，不依赖宿主源码或宿主构建目录。

`verify` 的两个集成测试真实启动 Runner 发行 JAR，只把 Runner 放入子进程类路径，覆盖初始化、模型检查和探测、加载、普通对话、Unicode 流、嵌入、卸载及取消。可用 `-Dplugin.runner.jar=/绝对路径/plugin-runner.jar` 指定已发行 Runner。

生成带完整文件哈希的本地未签名包后，在插件中心预览并确认来源与权限：

```bash
java -cp plugin-runner-java/target/plugin-runner-java-4.0.0-SNAPSHOT-all.jar \
  com.javaclaw.plugin.sdk.packaging.PluginPackageMain \
  --manifest sample-plugins/echo-model/plugin.json \
  --artifact sample-plugins/echo-model/target/plugin.jar \
  --output sample-plugins/echo-model/target/echo-model-2.0.0.zip
```

需要签名时使用 SDK CLI 的 `--private-key` 和 `--public-key` 读取外部 Ed25519 密钥；私钥不进入插件包和仓库。
