package com.javaclaw.task.sdd.verify;

/**
 * 命令执行端口 —— {@link ScenarioVerifier} 用它核验 {@code command_exit_zero} /
 * {@code output_contains} 类谓词，不直接耦合项目的 CommandLineTools / ProcessBuilder。
 *
 * <p>由编排层（B3/B5）注入具体实现（接到项目命令行工具或受工作目录约束的进程执行）。
 * 这样验证层保持纯净、可独立测试。</p>
 *
 * @author JavaClaw
 */
@FunctionalInterface
public interface CommandRunner {

    /**
     * 在指定工作目录执行命令。实现需自行处理超时与异常，异常应折成非零退出码的结果而非抛出。
     *
     * @param command 命令文本
     * @param workDir 工作目录绝对路径（可空，实现自决默认目录）
     * @return 执行结果（退出码 + 合并输出）
     */
    Result run(String command, String workDir);

    /**
     * 命令执行结果。
     *
     * @param exitCode 退出码（0 表示成功；约定负数表示执行异常/超时）
     * @param output   stdout+stderr 合并文本（可空）
     */
    record Result(int exitCode, String output) {
        public boolean success() {
            return exitCode == 0;
        }
    }
}
