package com.javaclaw.desktop.shell;

/**
 * Desktop 的普通 Java 启动入口，同时支持发行包 classpath 和 IDEA 模块启动。
 *
 * <p>此类不继承 JavaFX Application，避免 JDK 启动器在 classpath 模式下要求 JavaFX 必须位于模块路径； JavaFX 生命周期仍由 JavaClawDesktop 管理。
 */
public final class JavaClawDesktopMain {
    private JavaClawDesktopMain() {}

    /**
     * 将参数原样交给 Desktop 的 JavaFX 生命周期入口。
     *
     * @param arguments JavaFX 参数；transport 仍只从显式 JVM 属性读取
     */
    public static void main(String[] arguments) {
        JavaClawDesktop.main(arguments);
    }
}
