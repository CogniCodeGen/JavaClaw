package com.javaclaw.nativehost.credential;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 系统凭据命令的有界进程执行器。
 *
 * <p>实现说明：stdout 和 stderr 必须并发消费，避免系统工具因管道写满而死锁；stderr 只用于排空，不进入异常文本。
 */
final class SystemCredentialCommandRunner implements CredentialCommandRunner {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int OUTPUT_LIMIT = 64 * 1024;

    @Override
    public Result run(List<String> command, byte[] standardInput) {
        List<String> checkedCommand = List.copyOf(Objects.requireNonNull(command, "command"));
        byte[] checkedInput =
                Objects.requireNonNull(standardInput, "standardInput").clone();
        try {
            return runProcess(checkedCommand, checkedInput);
        } finally {
            // 输入副本的所有权覆盖进程启动，启动失败也必须清零。
            java.util.Arrays.fill(checkedInput, (byte) 0);
        }
    }

    private Result runProcess(List<String> checkedCommand, byte[] checkedInput) {
        Process process = start(checkedCommand);
        CompletableFuture<byte[]> output = read(process.getInputStream());
        CompletableFuture<byte[]> error = read(process.getErrorStream());
        try {
            write(process.getOutputStream(), checkedInput);
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new MasterKeyProtectionException("系统凭据操作超时");
            }
            byte[] errorBytes = error.join();
            byte[] outputBytes = output.join();
            try {
                return new Result(process.exitValue(), outputBytes);
            } finally {
                java.util.Arrays.fill(errorBytes, (byte) 0);
                java.util.Arrays.fill(outputBytes, (byte) 0);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new MasterKeyProtectionException("系统凭据操作被中断", interrupted);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private Process start(List<String> command) {
        try {
            return new ProcessBuilder(command).start();
        } catch (IOException failure) {
            throw new MasterKeyProtectionException("无法启动系统凭据设施", failure);
        }
    }

    private CompletableFuture<byte[]> read(InputStream input) {
        return CompletableFuture.supplyAsync(() -> {
            try (input) {
                byte[] bytes = input.readNBytes(OUTPUT_LIMIT + 1);
                if (bytes.length > OUTPUT_LIMIT) {
                    throw new MasterKeyProtectionException("系统凭据设施返回数据过大");
                }
                return bytes;
            } catch (IOException failure) {
                throw new MasterKeyProtectionException("无法读取系统凭据设施响应", failure);
            }
        });
    }

    private void write(OutputStream output, byte[] input) {
        try (output) {
            output.write(input);
            output.flush();
        } catch (IOException failure) {
            throw new MasterKeyProtectionException("无法提交系统凭据请求", failure);
        }
    }
}
