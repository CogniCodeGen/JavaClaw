package com.javaclaw.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import com.javaclaw.sdk.AppServerProcess;
import com.javaclaw.sdk.ApprovalRequestedNotification;
import com.javaclaw.sdk.ClientNotification;
import com.javaclaw.sdk.EventNotification;
import com.javaclaw.sdk.ItemDeltaNotification;
import com.javaclaw.sdk.JavaClawClient;
import com.javaclaw.sdk.McpAuthorizationRequestedNotification;
import com.javaclaw.sdk.ResyncRequiredNotification;
import com.javaclaw.sdk.RpcException;
import com.javaclaw.sdk.UserInputRequestedNotification;
import com.javaclaw.sdk.WindowsTransportBridge;
import com.javaclaw.sdk.model.JsonDocument;
import com.javaclaw.sdk.model.ServerInfo;
import com.javaclaw.sdk.model.ThreadInfo;
import com.javaclaw.sdk.model.ThreadSnapshot;
import com.javaclaw.sdk.model.TurnInfo;
import com.javaclaw.sdk.model.TurnInput;
import com.javaclaw.sdk.model.TurnStartRequest;

/** 无头 SDK 客户端；仅使用领域 API，按持久执行结果返回稳定退出码，不依赖协议或 JSON 实现。 */
public final class JavaClawCli {
    public static final int EXIT_OK = 0;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_CONNECTION = 3;
    public static final int EXIT_RPC = 4;
    public static final int EXIT_TIMEOUT = 5;
    public static final int EXIT_INTERNAL = 6;
    public static final int EXIT_TURN_FAILED = 7;
    public static final int EXIT_INTERRUPTED = 130;
    private static final Set<String> BOOLEAN_OPTIONS = Set.of("json", "no-stream");

    private JavaClawCli() {}

    /** 解析本地 CLI 参数并通过 SDK 执行；人类可读或 JSON 输出写入 stdout，失败使用稳定退出码，不直连 Runtime。 */
    public static void main(String[] args) {
        int exit = execute(args, System.out, System.err);
        if (exit != EXIT_OK) {
            System.exit(exit);
        }
    }

    static int execute(String[] rawArgs, PrintStream out, PrintStream err) {
        boolean requestedJson = Arrays.asList(rawArgs).contains("--json");
        try {
            Arguments parsed = Arguments.parse(rawArgs);
            if (parsed.help || parsed.command == null) {
                out.println(usage());
                return EXIT_OK;
            }
            try (CliTransport transport = openTransport(parsed)) {
                JavaClawClient client = transport.client();
                ServerInfo server =
                        client.initialize("javaclaw-cli", "4.0.0-SNAPSHOT").join();
                Object result = executeCommand(client, server, parsed, out, err);
                writeResult(result, parsed.json, out);
                return commandExitCode(parsed, result);
            }
        } catch (IllegalArgumentException failure) {
            writeFailure(requestedJson, EXIT_USAGE, "usage", failure.getMessage(), null, err);
            if (!requestedJson) {
                err.println("Use --help for command usage.");
            }
            return EXIT_USAGE;
        } catch (CompletionException failure) {
            return writeAsyncFailure(requestedJson, failure.getCause(), err);
        } catch (RpcException failure) {
            writeFailure(requestedJson, EXIT_RPC, "rpc", failure.getMessage(), failure.data(), err);
            return EXIT_RPC;
        } catch (IOException failure) {
            writeFailure(requestedJson, EXIT_CONNECTION, "connection", failure.getMessage(), null, err);
            return EXIT_CONNECTION;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            writeFailure(requestedJson, EXIT_INTERRUPTED, "interrupted", "operation interrupted", null, err);
            return EXIT_INTERRUPTED;
        } catch (Exception failure) {
            writeFailure(requestedJson, EXIT_INTERNAL, "internal", safeMessage(failure), null, err);
            return EXIT_INTERNAL;
        }
    }

    private static CliTransport openTransport(Arguments arguments) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (arguments.value("socket") != null && arguments.value("pipe") != null) {
            throw new IllegalArgumentException("--socket and --pipe are mutually exclusive");
        }
        if (arguments.value("socket") != null) {
            if (os.contains("windows")) {
                throw new IllegalArgumentException("Windows requires --pipe, not a Unix socket");
            }
            var client = com.javaclaw.sdk.LocalSocketClient.connect(Path.of(arguments.required("socket")));
            return new CliTransport(client, client);
        }
        if (!os.contains("windows")) {
            if (arguments.value("pipe") != null) {
                throw new IllegalArgumentException("--pipe requires Windows");
            }
            AppServerProcess process = new AppServerProcess(arguments.serverCommand());
            return new CliTransport(process.client(), process);
        }
        String encoded = System.getenv("JAVACLAW_WINDOWS_TRANSPORT_COMMAND_JSON");
        if (encoded == null || encoded.isBlank()) {
            throw new IOException("Windows requires JAVACLAW_WINDOWS_TRANSPORT_COMMAND_JSON; "
                    + "stdio App Server fallback is disabled");
        }
        List<String> hostCommand = AppServerProcess.parseInfrastructureCommand(encoded);
        if (arguments.value("pipe") != null) {
            var client = WindowsTransportBridge.connect(hostCommand, arguments.required("pipe"));
            return new CliTransport(client, client);
        }
        String pipe = System.getenv()
                .getOrDefault(
                        "JAVACLAW_WINDOWS_PIPE",
                        "javaclaw-v4-cli-" + ProcessHandle.current().pid());
        Map<String, String> infrastructure = new LinkedHashMap<>();
        for (String name : List.of(
                "JAVACLAW_SANDBOX_MODULE_PATH",
                "JAVACLAW_BROWSER_SERVICE_LIB",
                "JAVACLAW_BROWSER_ASSET_DIR",
                "JAVACLAW_BROWSER_SERVICE_COMMAND_JSON")) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                infrastructure.put(name, value);
            }
        }
        WindowsTransportBridge.ManagedTransport managed = WindowsTransportBridge.startLocalAppServer(
                hostCommand, pipe, arguments.serverCommand(), infrastructure);
        return new CliTransport(managed.client(), managed);
    }

    static int writeAsyncFailure(boolean json, Throwable cause, PrintStream err) {
        Throwable failure = cause == null ? new IllegalStateException("asynchronous request failed") : cause;
        while ((failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (failure instanceof TimeoutException) {
            writeFailure(json, EXIT_TIMEOUT, "timeout", safeMessage(failure), null, err);
            return EXIT_TIMEOUT;
        }
        if (failure instanceof InterruptedException || failure instanceof CancellationException) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            writeFailure(json, EXIT_INTERRUPTED, "interrupted", "operation interrupted", null, err);
            return EXIT_INTERRUPTED;
        }
        if (failure instanceof RpcException rpc) {
            writeFailure(json, EXIT_RPC, "rpc", rpc.getMessage(), rpc.data(), err);
            return EXIT_RPC;
        }
        if (failure instanceof IOException) {
            writeFailure(json, EXIT_CONNECTION, "connection", safeMessage(failure), null, err);
            return EXIT_CONNECTION;
        }
        if (failure instanceof IllegalArgumentException) {
            writeFailure(json, EXIT_USAGE, "usage", safeMessage(failure), null, err);
            return EXIT_USAGE;
        }
        writeFailure(json, EXIT_INTERNAL, "internal", safeMessage(failure), null, err);
        return EXIT_INTERNAL;
    }

    static int commandExitCode(Arguments arguments, Object result) {
        ThreadSnapshot snapshot;
        String turnId;
        if ("run".equals(arguments.command) && result instanceof Map<?, ?> values) {
            snapshot = (ThreadSnapshot) values.get("transcript");
            turnId = ((TurnInfo) values.get("acceptedTurn")).id();
        } else if ("turn-await".equals(arguments.command) && result instanceof ThreadSnapshot value) {
            snapshot = value;
            turnId = arguments.required("turn");
        } else {
            return EXIT_OK;
        }
        // 传输成功不等于任务成功；仍输出完整 transcript，脚本可同时读取证据与失败退出码。
        TurnInfo terminal = snapshot.turns().stream()
                .filter(turn -> turn.id().equals(turnId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("completed Turn is missing from transcript"));
        return switch (terminal.status()) {
            case "COMPLETED" -> EXIT_OK;
            case "FAILED" -> EXIT_TURN_FAILED;
            case "INTERRUPTED", "CANCELLED" -> EXIT_INTERRUPTED;
            default -> throw new IllegalStateException("Turn did not reach a terminal state");
        };
    }

    static Object executeCommand(
            JavaClawClient client, ServerInfo server, Arguments args, PrintStream out, PrintStream err)
            throws Exception {
        return switch (args.command) {
            case "server-capabilities" -> server;
            case "workspace-create" ->
                client.workspaces()
                        .create(args.required("name"), Path.of(args.required("root")), args.value("idempotency-key"))
                        .join();
            case "workspace-list" -> client.workspaces().list().join();
            case "thread-start" ->
                client.threads()
                        .start(args.required("workspace"), args.value("title", ""), args.value("idempotency-key"))
                        .join();
            case "thread-list" ->
                client.threads()
                        .list(Boolean.parseBoolean(args.value("archived", "false")))
                        .join();
            case "thread-read" -> client.threads().read(args.required("thread")).join();
            case "profile-list" -> client.models().listProfiles().join();
            case "provider-list" -> client.models().listProviders().join();
            case "automation-list" -> client.automations().list().join();
            case "schedule-list" -> client.automations().listSchedules().join();
            case "skill-list" -> client.knowledge().listSkills().join();
            case "plugin-list" -> client.extensions().listPlugins().join();
            case "mcp-list" -> client.extensions().listMcpServers().join();
            case "mcp-discover" ->
                client.extensions().discoverMcp(args.required("mcp")).join();
            case "config-read" -> client.administration().readConfiguration().join();
            case "diagnostics-read" ->
                client.administration()
                        .readDiagnostics(args.intValue("limit", 100, 1, 10_000))
                        .join();
            case "run" -> runTurn(client, args, out, err);
            case "interrupt" ->
                Map.of(
                        "interrupted",
                        client.threads().interrupt(args.required("turn")).join());
            default -> CliManagementCommands.execute(client, args);
        };
    }

    private static Object runTurn(JavaClawClient client, Arguments args, PrintStream out, PrintStream err)
            throws Exception {
        String threadId = args.value("thread");
        if (threadId == null) {
            ThreadInfo thread = client.threads()
                    .start(
                            args.required("workspace"),
                            args.value("title", ""),
                            scopedKey(args.value("idempotency-key"), "thread"))
                    .join();
            threadId = thread.id();
        }
        client.threads().resume(threadId, 0).join();
        String selectedThread = threadId;
        boolean stream = !args.noStream;
        try (AutoCloseable listener = client.onNotification(notification -> {
            if (!selectedThread.equals(notificationThread(notification))) {
                return;
            }
            if (stream) {
                writeNotification(notification, args.json, out, err);
            }
        })) {
            var inputs = new ArrayList<TurnInput>();
            if (args.value("prompt") != null) {
                inputs.add(new TurnInput.Text(args.required("prompt")));
            }
            if (args.value("attachment") != null) {
                Path file = Path.of(args.required("attachment"));
                var attachment = client.attachments()
                        .upload(
                                file,
                                args.value("media-type", "application/octet-stream"),
                                scopedKey(args.key(), "attachment"))
                        .join();
                inputs.add(new TurnInput.Attachment(
                        attachment.sha256(),
                        attachment.mediaType(),
                        file.getFileName().toString()));
            }
            if (args.value("attachment-sha") != null) {
                inputs.add(new TurnInput.Attachment(
                        args.required("attachment-sha"),
                        args.value("media-type", "application/octet-stream"),
                        args.value("display-name", "attachment")));
            }
            if (inputs.isEmpty()) {
                throw new IllegalArgumentException("run requires --prompt, --attachment or --attachment-sha");
            }
            TurnStartRequest request = new TurnStartRequest(
                    threadId,
                    args.value("profile", "profile_chat"),
                    inputs,
                    TurnStartRequest.ApprovalMode.PROFILE_DEFAULT,
                    TurnStartRequest.ReasoningMode.PROFILE_DEFAULT,
                    scopedKey(args.value("idempotency-key"), "turn"));
            TurnInfo turn = client.threads().startTurn(request).join();
            long waitSeconds = args.longValue("wait-seconds", 600, 1, 86_400);
            // 通知用于展示，不能用于判定完成：旧轮事件重放或重连漏收均不改变指定 Turn 的持久终态。
            ThreadSnapshot completed = client.threads()
                    .awaitTurn(threadId, turn.id(), Duration.ofSeconds(waitSeconds))
                    .join();
            return Map.of("acceptedTurn", turn, "transcript", completed);
        }
    }

    private static String notificationThread(ClientNotification value) {
        if (value instanceof ItemDeltaNotification delta) {
            return delta.threadId();
        }
        if (value instanceof EventNotification event) {
            return event.event().threadId();
        }
        if (value instanceof ApprovalRequestedNotification approval) {
            return approval.threadId();
        }
        if (value instanceof UserInputRequestedNotification input) {
            return input.threadId();
        }
        if (value instanceof ResyncRequiredNotification resync) {
            return resync.threadId();
        }
        return "";
    }

    private static void writeNotification(
            ClientNotification notification, boolean jsonOutput, PrintStream out, PrintStream err) {
        synchronized (out) {
            if (jsonOutput) {
                out.println(JsonOutput.encode(Map.of("type", "notification", "value", notification)));
                return;
            }
            if (notification instanceof ItemDeltaNotification delta) {
                out.print(delta.text());
                out.flush();
            } else if (notification instanceof EventNotification event
                    && "item/completed".equals(event.event().type())) {
                out.println();
            } else if (notification instanceof ApprovalRequestedNotification approval) {
                err.println("Approval required: " + approval.reason());
            } else if (notification instanceof UserInputRequestedNotification input) {
                err.println("User input required: " + input.prompt());
            } else if (notification instanceof ResyncRequiredNotification) {
                err.println("Connection requires transcript resynchronization.");
            } else if (notification instanceof McpAuthorizationRequestedNotification authorization) {
                err.println("MCP authorization required: " + authorization.authorizationUrl());
            }
        }
    }

    private static void writeResult(Object result, boolean jsonOutput, PrintStream out) {
        if (jsonOutput) {
            out.println(JsonOutput.encode(Map.of("type", "result", "result", result)));
        } else {
            renderHuman(result, 0, out);
        }
    }

    static void renderHuman(Object value, int indent, PrintStream out) {
        String prefix = " ".repeat(Math.max(0, indent));
        if (value == null) {
            out.println(prefix + "null");
        } else if (scalar(value)) {
            out.println(prefix + humanScalar(value));
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.println(prefix + "(empty)");
            }
            map.forEach((key, child) -> renderNamed(String.valueOf(key), child, indent, out));
        } else if (value instanceof Iterable<?> values) {
            boolean empty = true;
            for (Object child : values) {
                empty = false;
                if (child == null || scalar(child)) {
                    out.println(prefix + "- " + humanScalar(child));
                } else {
                    out.println(prefix + "-");
                    renderHuman(child, indent + 2, out);
                }
            }
            if (empty) {
                out.println(prefix + "(none)");
            }
        } else if (value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                out.println(prefix + "-");
                renderHuman(Array.get(value, i), indent + 2, out);
            }
        } else if (value.getClass().isRecord()) {
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                renderNamed(component.getName(), recordValue(value, component), indent, out);
            }
        } else {
            out.println(prefix + value);
        }
    }

    private static void renderNamed(String name, Object value, int indent, PrintStream out) {
        String prefix = " ".repeat(Math.max(0, indent));
        if (value == null || scalar(value)) {
            out.println(prefix + name + ": " + humanScalar(value));
        } else {
            out.println(prefix + name + ":");
            renderHuman(value, indent + 2, out);
        }
    }

    private static boolean scalar(Object value) {
        return value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Enum<?>
                || value instanceof Path
                || value instanceof java.net.URI
                || value instanceof TemporalAccessor
                || value instanceof JsonDocument;
    }

    private static String humanScalar(Object value) {
        if (value == null) {
            return "null";
        }
        String text = value instanceof JsonDocument json ? json.canonicalJson() : String.valueOf(value);
        return text.replace("\r", "\\r").replace("\n", "\\n");
    }

    private static void writeFailure(
            boolean jsonOutput, int exitCode, String category, String message, JsonDocument data, PrintStream err) {
        if (!jsonOutput) {
            err.println("javaclaw: " + category + ": " + message);
            return;
        }
        LinkedHashMap<String, Object> failure = new LinkedHashMap<>();
        failure.put("type", "error");
        failure.put("exitCode", exitCode);
        failure.put("category", category);
        failure.put("message", message == null ? "request failed" : message);
        if (data != null) {
            failure.put("data", data);
        }
        err.println(JsonOutput.encode(failure));
    }

    private static String scopedKey(String value, String scope) {
        return value == null || value.isBlank() ? value : value + "-" + scope;
    }

    private static String safeMessage(Throwable failure) {
        return failure.getMessage() == null || failure.getMessage().isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }

    private static Object recordValue(Object record, RecordComponent component) {
        try {
            return component.getAccessor().invoke(record);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot render record", failure);
        }
    }

    private static String usage() {
        return """
                JavaClaw 4.0 CLI

                Global options:
                  --server-classpath <classpath>  App Server runtime classpath
                  --program-dir <path>            parent for default .javaclaw storage
                  --data-dir <path>               v4 data root
                  --config-dir <path>             credential-key configuration root
                  --cache-dir <path>              worktree and disposable cache root
                  --json                          JSON Lines output
                  --socket <path>                 Connect to an existing macOS/Linux App Server
                  --pipe <name>                   Connect through the Windows Transport Host

                Commands:
                  server-capabilities
                  workspace-create --name <name> --root <path> [--idempotency-key <key>]
                  workspace-list
                  thread-start --workspace <id> [--title <text>]
                  thread-list [--archived true]
                  thread-read --thread <id>
                  profile-list | provider-list | automation-list | schedule-list
                  skill-list | plugin-list | mcp-list | mcp-discover --mcp <id>
                  config-read | diagnostics-read [--limit 100]
                  run [--thread <id> | --workspace <id>] --prompt <text>
                      [--profile profile_chat] [--wait-seconds 600] [--no-stream]
                  interrupt --turn <id>

                Resource management (read the corresponding --json output before editing):
                  workspace-read/update/delete --workspace <id> [--revision <n>]
                  instructions-resolve --workspace <id>
                  thread-resume/events/fork/update/archive/unarchive/delete --thread <id>
                  turn-await --thread <id> --turn <id> | turn-steer --turn <id> --text <text>
                  approval-respond --approval <id> --approved true|false
                  input-respond --request <id> --text <text> [--cancelled true]
                  plan-adopt --thread <id> --item <id> --profile <id> --revision <n> --confirm true
                  thread-compact-start --thread <id> --confirm true
                  worktree-list --workspace <id>
                  worktree-patch --thread <child-id> --revision <n>
                  worktree-cleanup --thread <child-id> --revision <n> --discard-unmerged true --confirm true
                  model-list | tool-list | profile-read --profile <id>
                  profile-put --file <ProfileInfo.json> | profile-delete --profile <id> --revision <n>
                  profile-prompt-preview --profile <id> --workspace <id>
                  profile-prompt-optimize --thread <id> --profile <id> --revision <n> --file <draft.txt>
                  provider-configure --provider <id> --revision <n> --file <string-map.json>
                  provider-credential-set --provider <id> --credential-env <NAME>
                  provider-credential-clear --provider <id> --revision <n> --confirm true
                  automation-read/definition/items/start/resume/interrupt --automation <id>
                  automation-put --file <AutomationInfo.json>
                  sdd-import-preview --file <change.zip>
                  sdd-import --automation <id> --revision <n> --file <change.zip> --confirm true
                  sdd-export --automation <id> --output <change.zip>
                  automation-delete --automation <id> --revision <n> --confirm true
                  schedule-read/enable/disable/delete/trigger --schedule <id> [--revision <n>]
                  schedule-put --file <ScheduleInfo.json>
                  memory-list/proposals --workspace <id> | memory-read/history --memory <id>
                  memory-put/propose --file <MemoryDetailInfo.json> [--reason <text>]
                  memory-delete/restore --memory <id> --revision <n> [--source-revision <n>]
                  memory-review --proposal <id> --revision <n> --accept true|false
                  knowledge-list/search --workspace <id> [--query <text>]
                  knowledge-import --workspace <id> --name <name> --file <path> --media-type <mime>
                  knowledge-read/history/content/reindex/delete --source <id> [--revision <n>]
                  skill-read/history --skill <id> | skill-resource --skill <id> --revision <n> --path <relative>
                  skill-import-preview/install --file <bundle.zip> [--confirm true]
                  skill-export --skill <id> --output <bundle.zip>
                  skill-enable/disable/uninstall/restore --skill <id> --revision <n> [--source-revision <n>]
                  skill-proposals --workspace <id> | skill-propose --file <SkillLearningDraft.json> --reason <text>
                  skill-review --proposal <id> --revision <n> --accept true|false
                  learning-read/put --workspace <id> [--mode OFF|SUGGEST|AUTO --revision <n> --confirm true]
                  plugin-read/health/enable/disable/uninstall --plugin <id> [--revision <n>]
                  plugin-preview/install --file <bundle.zip> [--confirm true --source-confirmed true --permissions-approved true]
                  plugin-trust-list | plugin-trust-add --trust-key <id> --file <public-key.der> --label <text> --revision <n> --confirm true
                  plugin-trust-remove --trust-key <id> --revision <n> --confirm true
                  mcp-read/health/discover/enable/disable --mcp <id> [--revision <n>]
                  mcp-configure --mcp <id> --name <name> --file <metadata.json> --revision <n>
                  mcp-authorize-start --mcp <id> --confirm true | mcp-authorize-cancel --authorization <id>
                  mcp-credential-read/set/clear --mcp <id> [--credential-env <NAME> | --revision <n>]
                  site-list --workspace <id> | site-put --file <BrowserSiteInfo.json> --confirm true
                  site-delete --site <id> --revision <n> --confirm true
                  site-credential-read/set/clear --site <id> --name <slot> [--credential-env <NAME> | --revision <n>]
                  site-login-start --site <id> --revision <n> --confirm true
                  site-login-finish --session <id> --save true|false | site-session-read/clear --site <id>
                  network-grant-list --workspace <id> | network-grant-put --file <NetworkGrantInfo.json> --confirm true
                  network-grant-delete --grant <id> --revision <n> --confirm true
                  tool-authorization-options/list --workspace <id>
                  tool-authorization-put --file <ToolAuthorizationInfo.json> --confirm true
                  tool-authorization-delete --authorization <id> --revision <n> --confirm true
                  attachment-upload --file <path> --media-type <mime>
                  attachment-upload-start --sha <hash> --size <bytes> --display-name <name>
                  attachment-upload-chunk --upload <id> --offset <bytes> --file <chunk>
                  attachment-upload-complete --upload <id>
                  attachment-read/download/release --sha <hash> [--output <path>]
                  config-update --file <merge-patch.json> | diagnostics-export | theme-set --theme <id>

                Delete/restore/trust/grant operations require --confirm true.
                JSON documents contain SDK model fields, including revision; no Wire or raw RPC fields.
                Model calls, browser login and OAuth start require a persistent --socket/--pipe endpoint,
                except run, which waits for completion. A temporary App Server stops when this CLI exits.
                Output files are not overwritten unless --replace true. Credentials are never accepted
                as command-line values. All mutations accept --idempotency-key; absent keys are generated.

                All commands use typed SDK domain clients; raw JSON-RPC invocation is not exposed.
                Stable exit codes: 0 success, 2 usage, 3 connection, 4 RPC error,
                5 timeout, 6 internal failure, 7 Turn failed, 130 interrupted.
                """;
    }

    static final class Arguments {
        final String command;
        private final Map<String, String> values;
        private final boolean help;
        final boolean json;
        final boolean noStream;
        private final String generatedKey = java.util.UUID.randomUUID().toString();

        private Arguments(String command, Map<String, String> values, boolean help, boolean json, boolean noStream) {
            this.command = command;
            this.values = values;
            this.help = help;
            this.json = json;
            this.noStream = noStream;
        }

        static Arguments parse(String[] args) {
            String command = null;
            boolean help = false;
            boolean json = false;
            boolean noStream = false;
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index < args.length; index++) {
                String token = args[index];
                if ("--help".equals(token) || "-h".equals(token)) {
                    help = true;
                } else if ("--json".equals(token)) {
                    json = true;
                } else if ("--no-stream".equals(token)) {
                    noStream = true;
                } else if (!token.startsWith("--") && command == null) {
                    command = token;
                } else if (token.startsWith("--")) {
                    String name = token.substring(2);
                    if (BOOLEAN_OPTIONS.contains(name)) {
                        throw new IllegalArgumentException("unexpected boolean option: " + token);
                    }
                    if (index + 1 == args.length) {
                        throw new IllegalArgumentException(token + " requires a value");
                    }
                    String value = args[++index];
                    if (value.startsWith("--") || values.putIfAbsent(name, value) != null) {
                        throw new IllegalArgumentException("missing or duplicate value for " + token);
                    }
                } else {
                    throw new IllegalArgumentException("unexpected argument: " + token);
                }
            }
            return new Arguments(command, Map.copyOf(values), help, json, noStream);
        }

        String required(String name) {
            String value = values.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("--" + name + " is required");
            }
            return value;
        }

        String value(String name) {
            return name.equals("idempotency-key") ? key() : values.get(name);
        }

        String value(String name, String fallback) {
            return values.getOrDefault(name, fallback);
        }

        String key() {
            return value("idempotency-key", generatedKey);
        }

        long revision() {
            required("revision");
            return longValue("revision", 0, 0, Long.MAX_VALUE);
        }

        boolean booleanValue(String name, boolean fallback) {
            String value = value(name);
            if (value == null) {
                return fallback;
            }
            if (!value.equals("true") && !value.equals("false")) {
                throw new IllegalArgumentException("--" + name + " must be true or false");
            }
            return Boolean.parseBoolean(value);
        }

        void confirm() {
            if (!booleanValue("confirm", false)) {
                throw new IllegalArgumentException("this operation requires explicit --confirm true");
            }
        }

        void requirePersistent() {
            if (value("socket") == null && value("pipe") == null) {
                throw new IllegalArgumentException(
                        "this asynchronous operation requires --socket or --pipe; closing a temporary server would cancel it");
            }
        }

        int intValue(String name, int fallback, int minimum, int maximum) {
            return Math.toIntExact(longValue(name, fallback, minimum, maximum));
        }

        long longValue(String name, long fallback, long minimum, long maximum) {
            String raw = values.get(name);
            long value;
            try {
                value = raw == null ? fallback : Long.parseLong(raw);
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("--" + name + " must be an integer");
            }
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException("--" + name + " must be between " + minimum + " and " + maximum);
            }
            return value;
        }

        List<String> serverCommand() {
            String classpath = values.get("server-classpath");
            if (classpath == null || classpath.isBlank()) {
                classpath = System.getenv("JAVACLAW_APP_SERVER_CLASSPATH");
            }
            if (classpath == null || classpath.isBlank()) {
                throw new IllegalArgumentException("--server-classpath or JAVACLAW_APP_SERVER_CLASSPATH is required");
            }
            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-cp");
            command.add(classpath);
            command.add("com.javaclaw.server.bootstrap.AppServerMain");
            for (String option : List.of("program-dir", "data-dir", "config-dir", "cache-dir")) {
                String value = values.get(option);
                if (value != null) {
                    command.add("--" + option);
                    command.add(value);
                }
            }
            return List.copyOf(command);
        }
    }

    private static final class JsonOutput {
        private JsonOutput() {}

        static String encode(Object value) {
            StringBuilder result = new StringBuilder();
            append(result, value);
            return result.toString();
        }

        private static void append(StringBuilder out, Object value) {
            if (value == null) {
                out.append("null");
            } else if (value instanceof JsonDocument document) {
                out.append(document.canonicalJson());
            } else if (value instanceof java.util.Optional<?> optional) {
                append(out, optional.orElse(null));
            } else if (value instanceof CharSequence
                    || value instanceof Character
                    || value instanceof Enum<?>
                    || value instanceof Path
                    || value instanceof java.net.URI
                    || value instanceof TemporalAccessor) {
                quote(out, String.valueOf(value));
            } else if (value instanceof Number || value instanceof Boolean) {
                out.append(value);
            } else if (value instanceof byte[] bytes) {
                quote(out, Base64.getEncoder().encodeToString(bytes));
            } else if (value instanceof Map<?, ?> map) {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    quote(out, String.valueOf(entry.getKey()));
                    out.append(':');
                    append(out, entry.getValue());
                }
                out.append('}');
            } else if (value instanceof Iterable<?> values) {
                out.append('[');
                boolean first = true;
                for (Object item : values) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    append(out, item);
                }
                out.append(']');
            } else if (value.getClass().isArray()) {
                out.append('[');
                for (int index = 0; index < Array.getLength(value); index++) {
                    if (index > 0) {
                        out.append(',');
                    }
                    append(out, Array.get(value, index));
                }
                out.append(']');
            } else if (value.getClass().isRecord()) {
                out.append('{');
                RecordComponent[] components = value.getClass().getRecordComponents();
                for (int index = 0; index < components.length; index++) {
                    if (index > 0) {
                        out.append(',');
                    }
                    quote(out, components[index].getName());
                    out.append(':');
                    append(out, recordValue(value, components[index]));
                }
                out.append('}');
            } else {
                quote(out, String.valueOf(value));
            }
        }

        private static void quote(StringBuilder out, String value) {
            out.append('"');
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                switch (character) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\b' -> out.append("\\b");
                    case '\f' -> out.append("\\f");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (character < 0x20) {
                            out.append(String.format("\\u%04x", (int) character));
                        } else {
                            out.append(character);
                        }
                    }
                }
            }
            out.append('"');
        }
    }

    private record CliTransport(JavaClawClient client, AutoCloseable owner) implements AutoCloseable {
        private CliTransport {
            java.util.Objects.requireNonNull(client, "client");
            java.util.Objects.requireNonNull(owner, "owner");
        }

        @Override
        public void close() throws Exception {
            owner.close();
        }
    }
}
