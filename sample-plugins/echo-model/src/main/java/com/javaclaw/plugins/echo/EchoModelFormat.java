package com.javaclaw.plugins.echo;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** 专用于协议验证的本地格式，与任何真实模型引擎的配置文件无关。 */
final class EchoModelFormat {
    static final String FILE_NAME = "echo-model.json";
    static final String FORMAT = "javaclaw.echo.v1";

    static Descriptor inspect(String path) throws Exception {
        Path root = Path.of(path).toAbsolutePath().normalize();
        Path file = root.resolve(FILE_NAME);
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) > 65_536) throw new IllegalArgumentException("Echo 模型目录无效");
        JsonObject value = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        if (!FORMAT.equals(text(value, "format", ""))) throw new IllegalArgumentException("Echo 模型格式版本无效");
        String name = text(value, "name", "协议验证模型");
        String prefix = text(value, "prefix", "回声：");
        int context = number(value, "contextLength", 4096), dimensions = number(value, "embeddingDimensions", 8);
        if (name.isBlank() || name.length() > 256 || prefix.length() > 128 || context < 1 || context > 65_536
                || dimensions < 1 || dimensions > 64) throw new IllegalArgumentException("Echo 模型参数超出范围");
        return new Descriptor(name, prefix, context, dimensions, Files.size(file));
    }

    static String text(JsonObject value, String key, String fallback) {
        return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : fallback;
    }
    static int number(JsonObject value, String key, int fallback) {
        return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsInt() : fallback;
    }
    record Descriptor(String name, String prefix, int contextLength, int dimensions, long sizeBytes) { }
}
