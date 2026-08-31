package com.javaclaw.agent.tools;

import java.nio.charset.StandardCharsets;
import jdk.jshell.JShell;
import jdk.jshell.Snippet;

/** 一次性代码 Worker；JShell 的 local engine 只存在于被 OS 沙箱限制的子 JVM，不进入 App Server。 */
public final class JShellWorkerMain {
    private JShellWorkerMain() {}

    /** 从管道读取最多 1 MiB UTF-8 代码并顺序执行；编译或运行失败使用非零退出码。 */
    public static void main(String[] args) {
        try {
            byte[] source = System.in.readNBytes(1_048_577);
            if (source.length > 1_048_576) {
                throw new IllegalArgumentException("script exceeds one MiB");
            }
            String remaining = new String(source, StandardCharsets.UTF_8);
            // 脚本只编译 JDK 与工作区类型，不扫描 App Server 的发行 classpath 或运行注解处理器。
            // 完整发行 JAR 的模块/Provider 元数据不能成为用户代码的隐式编译依赖。
            try (JShell shell = JShell.builder()
                    .compilerOptions("-classpath", "", "-proc:none")
                    .executionEngine("local")
                    .build()) {
                while (!remaining.isBlank()) {
                    var completion = shell.sourceCodeAnalysis().analyzeCompletion(remaining);
                    if (!completion.completeness().isComplete()
                            || completion.source().isBlank()) {
                        throw new IllegalArgumentException("incomplete Java snippet");
                    }
                    for (var event : shell.eval(completion.source())) {
                        if (event.status() == Snippet.Status.REJECTED || event.exception() != null) {
                            throw new IllegalStateException("Java snippet failed");
                        }
                        if (event.value() != null) {
                            System.out.println(event.value());
                        }
                    }
                    remaining = completion.remaining();
                }
            }
        } catch (Throwable failure) {
            System.err.println("Java script failed: " + failure.getClass().getSimpleName());
            // 基础设施异常只记录 JDK 类与方法位置，不打印异常正文、脚本、文件路径或凭据。
            int depth = 0;
            for (Throwable cause = failure; cause != null && depth++ < 4; cause = cause.getCause()) {
                System.err.println("JDK failure type: " + cause.getClass().getSimpleName());
                java.util.Arrays.stream(cause.getStackTrace())
                        .filter(frame -> frame.getClassName().startsWith("jdk.")
                                || frame.getClassName().startsWith("java.")
                                || frame.getClassName().startsWith("com.sun.tools.javac."))
                        .limit(3)
                        .forEach(frame -> System.err.println(frame.getClassName() + "." + frame.getMethodName()));
                if (cause.getCause() == cause) {
                    break;
                }
            }
            System.exit(2);
        }
    }
}
