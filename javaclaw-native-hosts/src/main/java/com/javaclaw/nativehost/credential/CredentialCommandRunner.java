package com.javaclaw.nativehost.credential;

import java.util.List;

/** 运行不把 Secret 放入 argv 的系统凭据命令。 */
interface CredentialCommandRunner {
    Result run(List<String> command, byte[] standardInput);

    /**
     * 有界命令结果。
     *
     * @param exitCode 进程退出码
     * @param standardOutput 标准输出副本
     */
    record Result(int exitCode, byte[] standardOutput) implements AutoCloseable {
        /** 复制命令输出，避免调用方随后修改内部状态。 */
        public Result {
            standardOutput = standardOutput.clone();
        }

        @Override
        public byte[] standardOutput() {
            return standardOutput.clone();
        }

        /** 清零执行器持有的输出副本。 */
        @Override
        public void close() {
            java.util.Arrays.fill(standardOutput, (byte) 0);
        }
    }
}
