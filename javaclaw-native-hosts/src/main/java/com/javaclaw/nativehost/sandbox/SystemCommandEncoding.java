package com.javaclaw.nativehost.sandbox;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.function.IntSupplier;

/** 系统命令默认编码；Windows 使用受信任 GetOEMCP 结果，不读取项目设置或通过 Shell 查询。 */
public final class SystemCommandEncoding {
    private SystemCommandEncoding() {}

    /**
     * 读取当前平台默认命令输出编码。
     *
     * @return Java 可识别的规范编码名称；Windows 查询失败时拒绝猜测
     */
    public static String current() {
        return resolve(System.getProperty("os.name", ""), SystemCommandEncoding::windowsCodePage);
    }

    static String resolve(String osName, IntSupplier windowsCodePage) {
        if (!osName.toLowerCase(Locale.ROOT).contains("windows")) {
            return "UTF-8";
        }
        int page = windowsCodePage.getAsInt();
        if (page <= 0) {
            throw new IllegalStateException("Windows OEM code page is unavailable");
        }
        return Charset.forName(page == 65001 ? "UTF-8" : "cp" + page).name();
    }

    private static int windowsCodePage() {
        // 符号及 MethodHandle 只在本次 Arena 有效；调用完成后释放库查找资源，不保留悬空 native 地址。
        try (Arena arena = Arena.ofConfined()) {
            var symbol = SymbolLookup.libraryLookup(
                            java.nio.file.Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"))
                                    .resolve("System32/kernel32.dll"),
                            arena)
                    .find("GetOEMCP")
                    .orElseThrow(() -> new IllegalStateException("GetOEMCP is unavailable"));
            var call = Linker.nativeLinker().downcallHandle(symbol, FunctionDescriptor.of(ValueLayout.JAVA_INT));
            return (int) call.invokeExact();
        } catch (Throwable failure) {
            throw new IllegalStateException("cannot read Windows OEM code page", failure);
        }
    }
}
