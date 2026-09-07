package com.javaclaw.nativehost.sandbox;

import java.nio.file.Path;
import java.util.Arrays;

import com.javaclaw.nativehost.ffm.WindowsAclEvidence;
import com.javaclaw.nativehost.ffm.WindowsSandbox;

/** Windows 批处理 helper；目标仍以无 shell argv 交给 AppContainer。 */
public final class WindowsSandboxExecMain {
    private static final int EXECUTION_FAILURE = 72;
    private static final int RESTORATION_FAILURE = 73;

    private WindowsSandboxExecMain() {}

    /**
     * 执行一次受控目标并以其退出码结束 helper。
     *
     * @param arguments WindowsSandboxCommandBuilder 生成的固定参数
     */
    public static void main(String[] arguments) {
        if (arguments.length < 3 || !WindowsHelperControl.OPTION.equals(arguments[0])) {
            System.exit(EXECUTION_FAILURE);
            return;
        }
        Path control = Path.of(arguments[1]);
        int exitCode;
        try {
            exitCode = execute(control, arguments);
            WindowsHelperControl.completed(control, exitCode);
        } catch (RuntimeException | java.io.IOException failure) {
            boolean restoration = WindowsHelperControl.restorationFailure(failure);
            try {
                WindowsHelperControl.failed(control, failure);
            } catch (java.io.IOException unavailable) {
                // 完成记录缺失也使父进程锁定 Workspace，不能回退根据目标退出码判断恢复成功。
                failure.addSuppressed(unavailable);
            }
            System.err.println("Windows Sandbox trusted helper failed");
            exitCode = restoration ? RESTORATION_FAILURE : EXECUTION_FAILURE;
        }
        System.exit(exitCode);
    }

    private static int execute(Path control, String[] arguments) throws java.io.IOException {
        try (var evidence = WindowsAclEvidence.open(control)) {
            return WindowsSandbox.run(
                    WindowsSandboxHelperArguments.parse(Arrays.copyOfRange(arguments, 2, arguments.length)));
        }
    }
}
