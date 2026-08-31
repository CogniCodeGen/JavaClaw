package com.javaclaw.nativehost.sandbox;

import java.util.List;

import com.javaclaw.nativehost.ffm.NativeResourceLimits;

/** Internal macOS helper that becomes the sandboxed target process via FFM execvp. */
public final class ProcessGroupExecMain {
    private ProcessGroupExecMain() {}

    /** 将非空 args 作为目标 argv，先确认自身进程组边界再 exec；仅供已隔离的 macOS Helper 使用。 */
    public static void main(String[] args) {
        if (args.length == 0) {
            throw new IllegalArgumentException("target argv is missing");
        }
        NativeResourceLimits.leadProcessGroupAndExec(List.of(args));
    }
}
