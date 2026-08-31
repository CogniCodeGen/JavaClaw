package com.javaclaw.agent.tool;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.javaclaw.agent.runtime.ItemSink;
import com.javaclaw.agent.runtime.TurnExecutionContext;
import com.javaclaw.core.api.ThreadItem;
import com.javaclaw.core.api.ToolDescriptor;
import com.javaclaw.sandbox.api.SandboxCommand;
import com.javaclaw.sandbox.api.SandboxPolicy;
import com.javaclaw.sandbox.api.SandboxSession;
import com.javaclaw.sandbox.api.SandboxSessionOptions;
import com.javaclaw.sandbox.api.SandboxSignal;

/** 每 Turn 独占的 PTY 工具集；会话不能跨 Turn 借用，退出或取消时由 Supervisor 回收整棵进程树。 */
public final class TerminalToolProvider implements ToolProvider {
    private static final String ID = "\"sessionId\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":80}";
    private final SandboxPolicy ceiling;

    /** 注册已验证的原生 Supervisor 能力；不支持 PTY 的平台由 openSession 失败关闭。 */
    public TerminalToolProvider(SandboxPolicy ceiling) {
        this.ceiling = java.util.Objects.requireNonNull(ceiling);
    }

    @Override
    public String id() {
        return "terminal";
    }

    @Override
    public List<RegisteredTool> tools(TurnExecutionContext context) {
        throw new IllegalStateException("terminal tools require an owned Turn snapshot");
    }

    @Override
    public Snapshot snapshot(TurnExecutionContext context) {
        var sessions = new Sessions(context);
        return new Snapshot(
                definitions().stream()
                        .map(definition -> bind(definition, sessions))
                        .toList(),
                List.of(),
                sessions);
    }

    @Override
    public List<ToolDescriptor> catalog() {
        return definitions().stream().map(Definition::descriptor).toList();
    }

    private List<Definition> definitions() {
        return List.of(
                definition(
                        "terminal_open",
                        "在本 Turn 的沙箱中启动 PTY；返回 sessionId，所有后续输入仍经过审批。",
                        false,
                        "\"argv\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":128,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":8192}},"
                                + "\"columns\":{\"type\":\"integer\",\"minimum\":20,\"maximum\":1000},"
                                + "\"rows\":{\"type\":\"integer\",\"minimum\":5,\"maximum\":1000}",
                        "[\"argv\"]",
                        sessions -> sessions::open),
                definition(
                        "terminal_read",
                        "读取当前 Turn PTY 的有界输出及退出状态，不阻塞超过一秒。",
                        true,
                        ID + ",\"waitMillis\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":1000}",
                        "[\"sessionId\"]",
                        sessions -> call -> sessions.poll(
                                sessions.get(call),
                                call.arguments().path("waitMillis").asInt(100))),
                definition(
                        "terminal_input",
                        "将有界文本写入本 Turn PTY；文本可能触发业务副作用，必须保留审批。",
                        false,
                        ID + ",\"text\":{\"type\":\"string\",\"maxLength\":16384},\"eof\":{\"type\":\"boolean\"}",
                        "[\"sessionId\",\"text\"]",
                        sessions -> call -> {
                            var session = sessions.get(call);
                            session.process.write(
                                    call.arguments().path("text").asText().getBytes(StandardCharsets.UTF_8));
                            if (call.arguments().path("eof").asBoolean()) {
                                session.process.closeInput();
                            }
                            return sessions.poll(session, 100);
                        }),
                definition(
                        "terminal_resize",
                        "调整本 Turn PTY 的字符尺寸；不支持的平台不会伪装成功。",
                        true,
                        ID + ",\"columns\":{\"type\":\"integer\",\"minimum\":20,\"maximum\":1000},"
                                + "\"rows\":{\"type\":\"integer\",\"minimum\":5,\"maximum\":1000}",
                        "[\"sessionId\",\"columns\",\"rows\"]",
                        sessions -> call -> {
                            var session = sessions.get(call);
                            session.process.resize(
                                    call.arguments().path("columns").asInt(),
                                    call.arguments().path("rows").asInt());
                            return sessions.poll(session, 0);
                        }),
                definition(
                        "terminal_signal",
                        "只向当前 Turn 管理的 PTY 发送受支持的信号。",
                        false,
                        ID + ",\"signal\":{\"enum\":[\"INTERRUPT\",\"TERMINATE\",\"KILL\"]}",
                        "[\"sessionId\",\"signal\"]",
                        sessions -> call -> {
                            var session = sessions.get(call);
                            session.process.signal(SandboxSignal.valueOf(
                                    call.arguments().path("signal").asText()));
                            return sessions.poll(session, 100);
                        }),
                definition(
                        "terminal_close",
                        "关闭并回收当前 Turn 的 PTY 与子进程，不影响其他任务。",
                        true,
                        ID,
                        "[\"sessionId\"]",
                        sessions -> call -> {
                            var session = sessions.get(call);
                            session.close();
                            return result(session.id, "", "CLOSED");
                        }));
    }

    private static Definition definition(
            String name,
            String description,
            boolean readOnly,
            String properties,
            String required,
            java.util.function.Function<Sessions, ToolHandler> handler) {
        String schema = "{\"type\":\"object\",\"additionalProperties\":false,\"required\":" + required
                + ",\"properties\":{" + properties + "}}";
        return new Definition(new ToolDescriptor(name, description, schema), readOnly, handler);
    }

    private RegisteredTool bind(Definition definition, Sessions sessions) {
        var registered = new RegisteredTool(
                definition.descriptor(),
                ToolOrigin.BUILTIN,
                definition.readOnly() ? ToolRisk.LOW : ToolRisk.HIGH,
                !definition.readOnly(),
                ceiling,
                definition.handler().apply(sessions));
        return definition.readOnly() ? registered.readOnly() : registered;
    }

    private record Definition(
            ToolDescriptor descriptor, boolean readOnly, java.util.function.Function<Sessions, ToolHandler> handler) {}

    private static ToolHandler.Result result(String id, String output, String state) {
        return new ToolHandler.Result(
                new ThreadItem.DynamicToolCall("terminal", Map.of("sessionId", id, "state", state, "output", output)),
                "sessionId=" + id + "\nstate=" + state + "\n" + output);
    }

    private static final class Sessions implements AutoCloseable {
        private final Map<String, Terminal> values = new LinkedHashMap<>();
        private final TurnExecutionContext turn;
        private final SecretRedactor redactor = new SecretRedactor(System.getenv());
        private boolean closed;

        private Sessions(TurnExecutionContext turn) {
            this.turn = turn;
        }

        private synchronized ToolHandler.Result open(ToolHandler.Context call) throws Exception {
            turn.throwIfInterrupted();
            values.values().removeIf(value -> value.ended);
            if (closed || values.size() >= 4) {
                throw new IllegalStateException("Turn terminal quota exceeded");
            }
            var argv = new ArrayList<String>();
            call.arguments().path("argv").forEach(value -> argv.add(value.asText()));
            String id = "pty_" + UUID.randomUUID().toString().replace("-", "");
            var emitter = call.events().start("commandExecution");
            Terminal terminal = null;
            try {
                SandboxSession process = call.sandbox()
                        .openSession(
                                new SandboxCommand(
                                        id,
                                        argv,
                                        call.call().config().workingDirectory(),
                                        System.getenv(),
                                        call.sandboxPolicy()),
                                SandboxSessionOptions.pty(
                                        call.arguments().path("columns").asInt(100),
                                        call.arguments().path("rows").asInt(30)));
                terminal = new Terminal(id, argv, process, emitter, redactor);
                values.put(id, terminal);
                return poll(terminal, 100);
            } catch (Exception failure) {
                if (terminal == null) {
                    emitter.fail("terminal_start_failed", "PTY 启动失败；未退化为未隔离进程", false);
                } else {
                    terminal.close();
                }
                throw failure;
            }
        }

        private synchronized Terminal get(ToolHandler.Context call) throws Exception {
            turn.throwIfInterrupted();
            Terminal terminal = values.get(call.arguments().path("sessionId").asText());
            if (closed || terminal == null) {
                throw new IllegalArgumentException("PTY does not belong to this Turn");
            }
            return terminal;
        }

        private ToolHandler.Result poll(Terminal terminal, int waitMillis) throws Exception {
            synchronized (terminal) {
                turn.throwIfInterrupted();
                if (terminal.ended) {
                    return result(terminal.id, "", terminal.state);
                }
                StringBuilder output = new StringBuilder();
                long deadline =
                        System.nanoTime() + Duration.ofMillis(waitMillis).toNanos();
                for (int count = 0; count < 64 && output.length() < 24_000; count++) {
                    turn.throwIfInterrupted();
                    var frame = terminal.process.read(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
                    if (frame == null) {
                        break;
                    }
                    switch (frame.kind()) {
                        case STDOUT, STDERR -> {
                            String value = terminal.accept(frame.data());
                            output.append(value, 0, Math.min(value.length(), 24_000 - output.length()));
                        }
                        case EXIT -> {
                            String value = terminal.text.finish();
                            terminal.append(value);
                            output.append(value, 0, Math.min(value.length(), 24_000 - output.length()));
                            terminal.complete(frame.exitCode(), frame.truncated());
                        }
                        case ERROR -> {
                            terminal.close();
                            throw new IllegalStateException("sandbox PTY reported a bounded execution failure");
                        }
                        default -> {}
                    }
                    if (terminal.ended) {
                        break;
                    }
                }
                return result(terminal.id, redactor.text(output.toString()), terminal.state);
            }
        }

        @Override
        public synchronized void close() {
            closed = true;
            values.values().forEach(Terminal::close);
            values.clear();
        }
    }

    private static final class Terminal implements AutoCloseable {
        private final String id;
        private final List<String> argv;
        private final SandboxSession process;
        private final ItemSink.ItemEmitter emitter;
        private final SecretRedactor redactor;
        private final TerminalTextBuffer text;
        private final StringBuilder transcript = new StringBuilder();
        private boolean truncated;
        private volatile boolean ended;
        private String state = "RUNNING";

        private Terminal(
                String id,
                List<String> argv,
                SandboxSession process,
                ItemSink.ItemEmitter emitter,
                SecretRedactor redactor) {
            this.id = id;
            this.argv = List.copyOf(argv);
            this.process = process;
            this.emitter = emitter;
            this.redactor = redactor;
            text = new TerminalTextBuffer(redactor);
        }

        private String accept(byte[] bytes) {
            String safe = text.accept(bytes);
            append(safe);
            return safe;
        }

        private void append(String value) {
            if (value.isEmpty()) {
                return;
            }
            int accepted = Math.min(value.length(), Math.max(0, 262_144 - transcript.length()));
            transcript.append(value, 0, accepted);
            truncated |= accepted != value.length();
            emitter.delta(com.javaclaw.core.api.ItemDelta.text(value));
        }

        private void complete(int exitCode, boolean outputTruncated) {
            ended = true;
            state = "EXITED:" + exitCode;
            emitter.complete(redactor.item(new ThreadItem.CommandExecution(
                    argv,
                    exitCode,
                    transcript.toString(),
                    "",
                    false,
                    truncated || outputTruncated || text.truncated())));
            process.close();
        }

        @Override
        public synchronized void close() {
            process.terminate();
            if (!ended) {
                ended = true;
                state = "CLOSED";
                emitter.fail("terminal_closed", "PTY 随 Turn 结束或显式关闭，未将未完成命令标为成功", false);
            }
        }
    }
}
