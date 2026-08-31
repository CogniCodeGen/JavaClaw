package com.javaclaw.nativehost.sandbox;

import java.util.List;
import java.util.Locale;

import com.javaclaw.nativehost.ffm.LinuxSecurity;
import com.javaclaw.nativehost.ffm.PosixPty;

/** Acquires the inherited PTY slave as controlling terminal, then execs the target. */
public final class PosixPtyExecMain {
    private PosixPtyExecMain() {}

    /** 解析 columns、rows、分隔符及目标 argv；在已有沙箱和继承 PTY 中建立控制终端，Linux 先安装 seccomp，成功 exec 后不再返回 JVM。 */
    public static void main(String[] args) {
        if (args.length < 4 || !"--".equals(args[2])) {
            throw new IllegalArgumentException("PTY dimensions or target command are missing");
        }
        int columns = parseDimension(args[0], "columns");
        int rows = parseDimension(args[1], "rows");
        List<String> target = java.util.Arrays.asList(args).subList(3, args.length);
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            LinuxSecurity.installBaselineSeccomp();
        }
        PosixPty.attachControllingTerminalAndExec(target, columns, rows);
    }

    private static int parseDimension(String value, String name) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > 1_000) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid PTY " + name, invalid);
        }
    }
}
