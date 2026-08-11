package com.javaclaw.task.sdd.agent;

import com.javaclaw.platform.process.ProcessRequest;
import com.javaclaw.platform.process.ProcessRunner;
import com.javaclaw.task.sdd.verify.CommandRunner;
import com.javaclaw.util.ProjectAccessPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

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

    private final ProcessRunner processes;
    private volatile long timeoutSeconds;

    public ProcessCommandRunner(ProcessRunner processes) {
        this(processes, 120);
    }

    public ProcessCommandRunner(ProcessRunner processes, long timeoutSeconds) {
        this.processes = Objects.requireNonNull(processes, "processes");
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
    }

    /** 调整单条核验命令的执行超时（秒）；≤0 忽略。 */
    public void setTimeoutSeconds(long seconds) {
        if (seconds > 0) this.timeoutSeconds = seconds;
    }

    @Override
    public Result run(String command, String workDir) {
        if (ProjectAccessPolicy.strictIsolationEnabled()) {
            return new Result(-1, "（" + ProjectAccessPolicy.unconfinedExecutionDeniedReason() + "）");
        }
        if (command == null || command.isBlank()) {
            return new Result(-1, "（空命令）");
        }
        try {
            ProcessRequest request = ProcessRequest.shell(
                            "sdd-verify", command, Duration.ofSeconds(timeoutSeconds))
                    .withOutputLimit(MAX_OUTPUT_CHARS);
            if (workDir != null && !workDir.isBlank()) {
                Path directory = Path.of(workDir).toAbsolutePath().normalize();
                if (java.nio.file.Files.isDirectory(directory)) {
                    request = request.withWorkingDirectory(directory);
                }
            }
            var result = processes.run(request);
            String output = merge(result.stdout(), result.stderr());
            if (result.timedOut()) {
                log.warn("[Verify] 命令超时（{}s）：{}", timeoutSeconds, command);
                return new Result(-2, output + "\n（命令执行超时 "
                        + timeoutSeconds + "s，已强制终止）");
            }
            if (result.outputTruncated()) {
                output += "\n（输出已截断）";
            }
            return new Result(result.exitCode(), output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-3, "（命令执行被中断）");
        } catch (Exception e) {
            log.warn("[Verify] 命令执行异常：{} — {}", command, e.getMessage());
            return new Result(-1, "（命令执行异常：" + e.getMessage() + "）");
        }
    }

    private static String merge(String stdout, String stderr) {
        String out = stdout == null ? "" : stdout;
        String err = stderr == null ? "" : stderr;
        if (out.isBlank()) return err;
        if (err.isBlank()) return out;
        return out + (out.endsWith("\n") ? "" : "\n") + err;
    }
}
