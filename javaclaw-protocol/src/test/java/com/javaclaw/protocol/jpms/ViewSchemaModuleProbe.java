package com.javaclaw.protocol.jpms;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ViewSchemaWireCodec;

/** 在独立 JVM 中检查命名 Protocol 模块能读取 classpath Server 输出的页面。 */
public final class ViewSchemaModuleProbe {
    private ViewSchemaModuleProbe() {}

    /**
     * 解码并重新编码每份输入，任何模块加载或 wire 契约失败均使进程异常退出。
     *
     * @param arguments 页面 JSON 文件路径
     * @throws IOException 页面文件无法读取时抛出
     */
    public static void main(String[] arguments) throws IOException {
        if (!CanonicalJson.class.getModule().isNamed()
                || ModuleLayer.boot()
                        .findModule("com.fasterxml.jackson.databind")
                        .isEmpty()) {
            throw new IllegalStateException("Protocol 和 Jackson 必须作为命名模块加载");
        }
        CanonicalJson json = new CanonicalJson();
        ViewSchemaWireCodec codec = new ViewSchemaWireCodec(json);
        for (String argument : arguments) {
            CanonicalPayload payload = json.parse(Files.readString(Path.of(argument)));
            if (!payload.equals(codec.encode(codec.decode(payload)))) {
                throw new IllegalStateException("命名模块页面编解码不得改变 wire 契约");
            }
        }
    }
}
