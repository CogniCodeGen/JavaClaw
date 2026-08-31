package com.javaclaw.agent.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从随发行物发布的锁定目录读取内置模板；资源损坏时失败关闭，不回退为无行为底座。 */
public final class PromptCatalog {
    private final Map<String, PromptTemplate> templates;

    /** 一次性加载并校验所有模板及哈希，不访问 GitHub 或用户文件。 */
    public PromptCatalog() {
        LinkedHashMap<String, PromptTemplate> loaded = new LinkedHashMap<>();
        for (String line : resource("manifest.tsv")
                .lines()
                .filter(value -> !value.isBlank() && !value.startsWith("#"))
                .toList()) {
            String[] fields = line.split("\\t", -1);
            if (fields.length != 3 || !fields[0].matches("[a-z_]+")) {
                throw new IllegalStateException("invalid built-in prompt manifest");
            }
            PromptTemplate template =
                    new PromptTemplate(fields[0], Integer.parseInt(fields[1]), resource(fields[0] + ".md"), fields[2]);
            if (loaded.putIfAbsent(template.id(), template) != null) {
                throw new IllegalStateException("duplicate prompt template: " + template.id());
            }
        }
        templates = Map.copyOf(loaded);
    }

    /** 返回指定内置模板；不存在时抛出异常，不加载任意相对路径。 */
    public PromptTemplate require(String id) {
        PromptTemplate value = templates.get(id);
        if (value == null) {
            throw new IllegalArgumentException("unknown prompt template: " + id);
        }
        return value;
    }

    /** 返回按 id 排序的不可变发行模板目录。 */
    public List<PromptTemplate> list() {
        return templates.values().stream()
                .sorted(java.util.Comparator.comparing(PromptTemplate::id))
                .toList();
    }

    private static String resource(String name) {
        try (var input = PromptCatalog.class.getResourceAsStream("/prompts/" + name)) {
            if (input == null) {
                throw new IllegalStateException("missing built-in prompt: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read built-in prompt: " + name, failure);
        }
    }
}
