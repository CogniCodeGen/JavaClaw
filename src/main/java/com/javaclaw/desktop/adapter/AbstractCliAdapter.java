package com.javaclaw.desktop.adapter;

import com.javaclaw.desktop.DesktopAutomationPort;
import com.javaclaw.desktop.DesktopException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * CLI 适配器基类 —— 把"调用命令行工具、超时控制、流读取"这套样板收敛到一处。
 *
 * <p>各 OS 适配器（macOS / Windows / Linux）只需关心"组什么命令、怎么解析输出"，
 * 进程执行、并发读流（避免缓冲区写满死锁）、超时强杀、退出码校验等共性逻辑都在这里。</p>
 *
 * <p>关键实现点：stdout / stderr 由独立守护线程并发抽空，再 {@code waitFor} 限时——
 * 这是 {@link ProcessBuilder} 的正确用法，避免大输出把管道写满导致子进程阻塞、父进程空等。</p>
 */
abstract class AbstractCliAdapter implements DesktopAutomationPort {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    /** 默认命令超时（秒）：CLI 桥（osascript / powershell / wmctrl）均为轻量查询，几秒足够。 */
    protected static final long DEFAULT_TIMEOUT_SECONDS = 15;

    /** 当前是否 Windows（决定 which/where 等差异）。 */
    protected static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** 单次命令执行结果。 */
    protected record CliResult(int exitCode, String stdout, String stderr) {
        boolean ok() {
            return exitCode == 0;
        }
    }

    /**
     * 执行命令并返回结果（带默认超时）。
     *
     * @param command 命令及参数（不经 shell，直接 exec，避免注入与转义问题）
     * @throws DesktopException 进程无法启动或超时
     */
    protected CliResult exec(String... command) {
        return exec(DEFAULT_TIMEOUT_SECONDS, command);
    }

    /** 执行命令并返回结果（指定超时秒数）。 */
    protected CliResult exec(long timeoutSeconds, String... command) {
        log.debug("CLI 执行: {}", String.join(" ", command));
        Process proc;
        try {
            proc = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new DesktopException("无法启动命令: " + command[0] + " — " + e.getMessage(), e);
        }
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread tOut = pump(proc.getInputStream(), out);
        Thread tErr = pump(proc.getErrorStream(), err);
        try {
            if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new DesktopException("命令超时(" + timeoutSeconds + "s): " + command[0]);
            }
            tOut.join(1000);
            tErr.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new DesktopException("命令执行被中断: " + command[0], e);
        }
        return new CliResult(proc.exitValue(), out.toString(), err.toString());
    }

    /** 起一个守护线程把输入流逐行抽到缓冲区，避免管道写满导致死锁。 */
    private Thread pump(InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sink.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // 进程结束后流关闭属正常，忽略
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** 探测某命令行工具是否存在（Windows 用 where，类 Unix 用 which）。 */
    protected boolean commandExists(String bin) {
        try {
            CliResult r = exec(5, IS_WINDOWS ? "where" : "which", bin);
            return r.ok() && !r.stdout().isBlank();
        } catch (DesktopException e) {
            return false;
        }
    }

    /** 把字符串里的 {@code ch} 转义为双写（用于安全嵌入 AppleScript / PowerShell 的引号字符串）。 */
    protected static String escapeDoubling(String s, char ch) {
        if (s == null) {
            return "";
        }
        return s.replace(String.valueOf(ch), String.valueOf(ch) + ch);
    }
}
