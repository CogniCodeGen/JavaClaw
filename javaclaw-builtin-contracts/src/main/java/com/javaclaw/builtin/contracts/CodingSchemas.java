package com.javaclaw.builtin.contracts;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.javaclaw.api.CanonicalPayload;

/** 发行版内不可变的 Coding v1 Schema；不从 Workspace 或网络读取代码或契约。 */
public final class CodingSchemas {
    private static final List<String> NAMES = List.of(
            "empty",
            "file-list-input",
            "file-read-input",
            "file-search-input",
            "apply-patch-input",
            "command-run-input",
            "terminal-open-input",
            "terminal-read-input",
            "terminal-write-input",
            "terminal-signal-input",
            "terminal-resize-input",
            "terminal-close-input",
            "dependencies-prepare-input",
            "file-list-result",
            "file-read-result",
            "file-search-result",
            "patch-result",
            "command-result",
            "terminal-result",
            "preparation-result",
            "toolchain-catalog",
            "toolchain-list",
            "toolchain-install-input",
            "toolchain-install-result",
            "environment",
            "environment-update-input",
            "resource-read-input",
            "output-read-input",
            "failure",
            "dependency-evidence",
            "execution-list");

    private CodingSchemas() {}

    /** @return 当前发行版包含的精确 Schema 名称 */
    public static List<String> names() {
        return NAMES;
    }

    /**
     * 读取规范键顺序的内置 Schema。
     *
     * @param name {@link #names()} 中的名称，不含路径或扩展名
     * @return 规范化 Schema 正文
     */
    public static CanonicalPayload read(String name) {
        if (!NAMES.contains(name)) {
            throw new IllegalArgumentException("unknown Coding schema");
        }
        try (InputStream input = CodingSchemas.class.getResourceAsStream("/coding/v1/" + name + ".json")) {
            if (input == null) {
                throw new IllegalStateException("missing Coding schema: " + name);
            }
            return new CanonicalPayload(new String(input.readAllBytes(), StandardCharsets.UTF_8).strip());
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read Coding schema: " + name, failure);
        }
    }

    /**
     * 返回全局 Schema 标识。
     *
     * @param name 已注册名称
     * @return Coding v1 Schema 标识
     */
    public static String id(String name) {
        if (!NAMES.contains(name)) {
            throw new IllegalArgumentException("unknown Coding schema");
        }
        return "javaclaw.coding/" + name + "/v1";
    }

    /**
     * 工具输出接受成功结果或稳定失败；独立事实 Schema 继续保持原有形状。
     *
     * @param name 成功结果 Schema 名称
     * @return 自包含 JSON Schema 联合
     */
    public static CanonicalPayload toolOutput(String name) {
        return new CanonicalPayload(
                "{\"anyOf\":[" + read(name).json() + "," + read("failure").json() + "],\"type\":\"object\"}");
    }
}
