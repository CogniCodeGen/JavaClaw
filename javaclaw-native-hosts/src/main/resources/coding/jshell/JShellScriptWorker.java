import java.io.ByteArrayInputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import jdk.jshell.DeclarationSnippet;
import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import jdk.jshell.SnippetEvent;
import jdk.jshell.SourceCodeAnalysis;
import jdk.jshell.execution.LocalExecutionControlProvider;

/**
 * 仅由冻结托管 JDK 在 OS Sandbox 内以 Java 21 源码启动的固定入口。
 *
 * <p>本源码作为发行资源维护，不编译进主应用运行路径。每个进程只创建一个 JShell，编译和执行均不进入 App Server。 local engine 不创建 JDI 网络连接；取消与超时由外部进程树监督负责，不能依赖 Java
 * 线程中断形成隔离。
 */
public final class JShellScriptWorker {
    private static final int MAX_SOURCE_BYTES = 65_536;
    private static final int SOURCE_FAILURE = 2;
    private static final int EVALUATION_FAILURE = 3;

    private JShellScriptWorker() {}

    /**
     * 从 stdin 读取一次 UTF-8 源码并执行；标准输出和错误输出仅供观察，不能作为平台控制协议。
     *
     * @param arguments 必须为空，运行配置只能由平台固定
     * @throws Exception 输入或 JShell 初始化失败时由 JVM 返回非零退出状态
     */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 0) {
            throw new IllegalArgumentException("JShell Worker does not accept arguments");
        }
        byte[] bytes = System.in.readNBytes(MAX_SOURCE_BYTES + 1);
        if (bytes.length > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("Java source exceeds 65536 UTF-8 bytes");
        }
        String source = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        if (source.isBlank() || source.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Java source must not be blank or contain NUL");
        }
        // 源码已全部读取，片段不能继续消费执行协议输入；每次调用的状态仅保留在当前独立 JVM。
        System.setIn(new ByteArrayInputStream(new byte[0]));
        PrintStream output = System.out;
        PrintStream error = System.err;
        int result;
        try (JShell shell = JShell.builder()
                .executionEngine(new LocalExecutionControlProvider(), Map.of())
                .in(new ByteArrayInputStream(new byte[0]))
                .out(output)
                .err(error)
                .build()) {
            result = evaluate(shell, source, output, error);
        }
        output.flush();
        error.flush();
        // 结束片段创建的普通线程；遗留子进程仍由父平台在同一个 Sandbox 进程树中清理。
        System.exit(result);
    }

    private static int evaluate(JShell shell, String source, PrintStream output, PrintStream error) {
        String remaining = source;
        while (!remaining.isBlank()) {
            SourceCodeAnalysis.CompletionInfo completion =
                    shell.sourceCodeAnalysis().analyzeCompletion(remaining);
            SourceCodeAnalysis.Completeness completeness = completion.completeness();
            if (completeness == SourceCodeAnalysis.Completeness.EMPTY) {
                break;
            }
            if (completeness == SourceCodeAnalysis.Completeness.DEFINITELY_INCOMPLETE
                    || completeness == SourceCodeAnalysis.Completeness.CONSIDERED_INCOMPLETE) {
                error.println("Java 片段不完整，已停止执行。");
                return SOURCE_FAILURE;
            }
            int result = events(shell, shell.eval(completion.source()), output, error);
            if (result != 0) {
                return result;
            }
            if (completion.remaining().equals(remaining)) {
                error.println("Java 片段分析无法继续，已停止执行。");
                return SOURCE_FAILURE;
            }
            remaining = completion.remaining();
        }
        return unresolved(shell, error);
    }

    private static int events(JShell shell, List<SnippetEvent> events, PrintStream output, PrintStream error) {
        for (SnippetEvent event : events) {
            if (event.status() == Snippet.Status.REJECTED) {
                diagnostics(shell, event.snippet(), error);
                return SOURCE_FAILURE;
            }
            if (event.exception() != null) {
                error.println("Java 片段执行失败：" + event.exception());
                event.exception().printStackTrace(error);
                return EVALUATION_FAILURE;
            }
            if (event.causeSnippet() == null && event.value() != null) {
                output.println(event.value());
            }
        }
        return 0;
    }

    private static int unresolved(JShell shell, PrintStream error) {
        for (Snippet snippet : shell.snippets().toList()) {
            Snippet.Status status = shell.status(snippet);
            if (status == Snippet.Status.RECOVERABLE_DEFINED || status == Snippet.Status.RECOVERABLE_NOT_DEFINED) {
                diagnostics(shell, snippet, error);
                if (snippet instanceof DeclarationSnippet declaration) {
                    shell.unresolvedDependencies(declaration).forEach(name -> error.println("未解析的 Java 引用：" + name));
                }
                return SOURCE_FAILURE;
            }
        }
        return 0;
    }

    private static void diagnostics(JShell shell, Snippet snippet, PrintStream error) {
        error.println("Java 片段编译失败（片段 " + snippet.id() + "）。");
        shell.diagnostics(snippet).forEach(diagnostic -> error.println(diagnostic.getMessage(Locale.ROOT)));
    }
}
