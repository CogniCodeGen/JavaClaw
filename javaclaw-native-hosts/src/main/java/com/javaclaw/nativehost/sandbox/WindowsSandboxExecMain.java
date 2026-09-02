package com.javaclaw.nativehost.sandbox;

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
        int exitCode;
        try {
            exitCode = WindowsSandbox.run(WindowsSandboxHelperArguments.parse(arguments));
        } catch (WindowsSandbox.AclRestorationException failure) {
            System.err.println("Windows Sandbox permission restoration failed");
            exitCode = RESTORATION_FAILURE;
        } catch (RuntimeException | java.io.IOException failure) {
            System.err.println(
                    "Windows Sandbox execution failed: " + failure.getClass().getSimpleName());
            exitCode = EXECUTION_FAILURE;
        }
        System.exit(exitCode);
    }
}
