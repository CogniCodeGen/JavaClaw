package com.javaclaw.nativehost;

import java.nio.file.Path;
import java.util.Objects;

/** 发行程序与开发启动共用的数据目录约定；不搜索或迁移历史数据目录。 */
public final class LocalRuntimeDirectories {
    private LocalRuntimeDirectories() {}

    /**
     * 返回发行启动器指定的程序目录，开发运行则使用当前工作目录。
     *
     * @return 规范绝对路径；调用本方法不会创建目录
     */
    public static Path programDirectory() {
        return Path.of(System.getProperty("javaclaw.program.dir", System.getProperty("user.dir"))
                        .strip())
                .toAbsolutePath()
                .normalize();
    }

    /**
     * 返回显式配置的 data-v6 或程序目录下的 data-v6。
     *
     * @return 规范绝对数据目录；历史版本目录不会被探测
     */
    public static Path dataDirectory() {
        return dataDirectory(programDirectory());
    }

    /**
     * 使用已校验的发行目录解析数据根；显式数据属性仍有最高优先级。
     *
     * @param programDirectory 程序目录，不得为空
     * @return 规范绝对数据根；不创建目录、不搜索历史版本
     */
    public static Path dataDirectory(Path programDirectory) {
        String configured = System.getProperty("javaclaw.data.root", "").strip();
        return configured.isEmpty()
                ? Objects.requireNonNull(programDirectory, "programDirectory")
                        .toAbsolutePath()
                        .normalize()
                        .resolve("data-v6")
                : Path.of(configured).toAbsolutePath().normalize();
    }
}
