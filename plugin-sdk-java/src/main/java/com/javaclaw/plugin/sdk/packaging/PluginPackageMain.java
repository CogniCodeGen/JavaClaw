package com.javaclaw.plugin.sdk.packaging;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 示例和发行流程共享的 ZIP 打包入口，不生成或保存发布者私钥。 */
public final class PluginPackageMain {
    private PluginPackageMain() { }
    public static void main(String[] args) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        Map<String, Path> files = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            if (index + 1 >= args.length) throw new IllegalArgumentException("参数必须使用 --名称 值");
            if (args[index].equals("--file")) {
                int equals = args[index + 1].indexOf('=');
                if (equals < 1) throw new IllegalArgumentException("附加文件参数为 包内路径=本地文件路径");
                String name = args[index + 1].substring(0, equals);
                if (files.putIfAbsent(name, Path.of(args[index + 1].substring(equals + 1))) != null) {
                    throw new IllegalArgumentException("附加文件路径重复");
                }
            } else if (!java.util.Set.of("--manifest", "--artifact", "--output", "--private-key", "--public-key").contains(args[index])
                    || options.putIfAbsent(args[index], args[index + 1]) != null) {
                throw new IllegalArgumentException("未知或重复的参数");
            }
        }
        boolean privateKey = options.containsKey("--private-key"), publicKey = options.containsKey("--public-key");
        if (privateKey != publicKey) throw new IllegalArgumentException("签名需要同时指定私钥与公钥文件");
        PluginPackages.SigningKey key = privateKey ? PluginPackages.SigningKey.read(
                Path.of(options.get("--private-key")), Path.of(options.get("--public-key"))) : null;
        Path output = PluginPackages.create(required(options, "--manifest"), required(options, "--artifact"),
                files, required(options, "--output"), key);
        System.out.println((key == null ? "已生成未签名本地插件包: " : "已生成 Ed25519 签名插件包: ") + output);
    }
    private static Path required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少参数: " + name);
        return Path.of(value);
    }
}
