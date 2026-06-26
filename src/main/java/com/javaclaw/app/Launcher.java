package com.javaclaw.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 应用启动器
 *
 * <p>在非模块化的 JavaFX 项目中，如果直接运行继承自 {@link javafx.application.Application}
 * 的类，JavaFX 运行时会检测到缺少 module-info.java 而报错。</p>
 *
 * <p>解决方案：通过一个普通的 Java 类（不继承 Application）作为入口点，
 * 间接调用 {@link JavaClawApp#main(String[])} 来启动 JavaFX 应用。</p>
 *
 * <p>此类是 Maven javafx-maven-plugin 配置中的 mainClass。</p>
 *
 * @author JavaClaw
 * @see JavaClawApp
 */
public class Launcher {

    private static final Logger log = LoggerFactory.getLogger(Launcher.class);

    /**
     * 程序入口
     *
     * @param args 命令行参数，将透传给 JavaFX Application
     */
    public static void main(String[] args) {
        log.info("JavaClaw 启动器开始运行");
        log.info("JDK 版本: {}", System.getProperty("java.version"));
        log.info("JavaFX 版本: {}", System.getProperty("javafx.version"));
        log.info("操作系统: {} {}", System.getProperty("os.name"), System.getProperty("os.arch"));

        // 委托给 JavaFX Application 启动
        JavaClawApp.main(args);
    }
}
