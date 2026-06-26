package com.javaclaw.task.sdd.agent;

import com.javaclaw.task.sdd.verify.CommandRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * {@link CommandRunner} 的进程实现 —— 在工作目录内以 {@code bash -c} 执行验收命令
 * （如 {@code mvn -q compile}、{@code test -f xxx}），捕获合并输出与退出码。
 *
 * <p>专供 {@code ScenarioVerifier} 核验 {@code command_exit_zero}/{@code output_contains} 谓词，
 * 与项目的交互式命令行工具（带白名单/会话/确认）解耦——验收命令是规格作者写定的只读式核验，
 * 直接受工作目录约束执行即可。带超时；异常/超时折成负退出码而非抛出。</p>
 *
 * @author JavaClaw
 */
public final class ProcessCommandRunner implements CommandRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessCommandRunner.class);
    private static final int MAX_OUTPUT_CHARS = 64 * 1024;

    private volatile long timeoutSeconds;

    public ProcessCommandRunner() {
        this(120);
    }

    public ProcessCommandRunner(long timeoutSeconds) {
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
    }

    /** 调整单条核验命令的执行超时（秒）；≤0 忽略。 */
    public void setTimeoutSeconds(long seconds) {
        if (seconds > 0) this.timeoutSeconds = seconds;
    }

    @Override
    public Result run(String command, String workDir) {
        if (command == null || command.isBlank()) {
            return new Result(-1, "（空命令）");
        }
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-lc", command);
            if (workDir != null && !workDir.isBlank()) {
                File dir = new File(workDir);
                if (dir.isDirectory()) pb.directory(dir);
            }
            pb.redirectErrorStream(true);
            proc = pb.start();

            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (out.length() < MAX_OUTPUT_CHARS) out.append(line).append('\n');
                }
            }
            boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                log.warn("[Verify] 命令超时（{}s）：{}", timeoutSeconds, command);
                return new Result(-2, out + "\n（命令执行超时 " + timeoutSeconds + "s，已强制终止）");
            }
            return new Result(proc.exitValue(), out.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (proc != null) proc.destroyForcibly();
            return new Result(-3, "（命令执行被中断）");
        } catch (Exception e) {
            log.warn("[Verify] 命令执行异常：{} — {}", command, e.getMessage());
            return new Result(-1, "（命令执行异常：" + e.getMessage() + "）");
        }
    }
}
