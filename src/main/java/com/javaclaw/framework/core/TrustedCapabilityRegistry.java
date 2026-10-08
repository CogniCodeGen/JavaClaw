package com.javaclaw.framework.core;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.javaclaw.framework.api.TaskCriterionV3;
import com.javaclaw.framework.api.PermissionSet;
import com.javaclaw.framework.api.CapabilityMetadata;
import com.javaclaw.agent.ToolRiskLevel;
import com.javaclaw.agent.ToolRiskRegistry;
import com.javaclaw.framework.spi.EffectReceiptV1;
import com.javaclaw.framework.spi.ToolDescriptor;
import com.javaclaw.framework.spi.ToolEffectPolicy;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Host-owned vocabulary shared by task planning and receipt verification. */
public final class TrustedCapabilityRegistry {
    private static final String BROWSER_PAGE_INPUT_TARGET =
            "; newly model-planned criteria target the current page URL immediately before input dispatch, even if the "
                    + "input navigates. Never assume the post-input result URL as this target; "
                    + "verify the requested resulting page or content with browser.observe. "
                    + "Previously frozen contracts and explicit definitions retain their declared "
                    + "target phase; a missing phase means the returned page";

    public enum TargetKind { FILE, URL, DESKTOP_APPLICATION, EMAIL_ADDRESS, SCHEDULE, COMMAND, RESOURCE }
    public enum VerifierPolicy {
        EXACT_HOST_RECEIPT, FILE_POSTCONDITION, DESKTOP_LINKED_FRAME,
        TRANSPORT_ACCEPTANCE, SCHEDULE_STATE, PROCESS_EXIT_ONLY
    }

    /** Runtime snapshot of a host tool's executable contract. */
    public record HostToolBinding(String tool, JsonNode inputSchema, JsonNode outputSchema,
            PermissionSet permissions, String risk, boolean idempotent,
            ToolEffectPolicy effectPolicy) {
        public HostToolBinding {
            tool = required(tool);
            inputSchema = Objects.requireNonNull(inputSchema, "inputSchema").deepCopy();
            outputSchema = Objects.requireNonNull(outputSchema, "outputSchema").deepCopy();
            permissions = Objects.requireNonNull(permissions, "permissions");
            risk = required(risk);
            effectPolicy = Objects.requireNonNull(effectPolicy, "effectPolicy");
        }
        @Override public JsonNode inputSchema() { return inputSchema.deepCopy(); }
        @Override public JsonNode outputSchema() { return outputSchema.deepCopy(); }
    }

    public record CapabilityDescriptor(String id, String description, String operation,
            TargetKind targetKind, EffectReceiptV1.Status evidenceCeiling,
            Set<String> trustedTools, VerifierPolicy verifierPolicy) {
        public CapabilityDescriptor {
            id = required(id);
            description = required(description);
            operation = required(operation);
            targetKind = Objects.requireNonNull(targetKind, "targetKind");
            evidenceCeiling = Objects.requireNonNull(evidenceCeiling, "evidenceCeiling");
            trustedTools = Set.copyOf(Objects.requireNonNull(trustedTools, "trustedTools"));
            verifierPolicy = Objects.requireNonNull(verifierPolicy, "verifierPolicy");
            if (trustedTools.isEmpty() || rank(evidenceCeiling) == 0)
                throw new IllegalArgumentException("invalid trusted capability");
        }

        public CapabilityDescriptor(String id, String description, String operation,
                TargetKind targetKind, EffectReceiptV1.Status evidenceCeiling,
                Set<String> trustedTools) {
            this(id, description, operation, targetKind, evidenceCeiling, trustedTools,
                    policyFor(targetKind));
        }
    }

    private final Map<String, CapabilityDescriptor> byId = new LinkedHashMap<>();
    private final Map<String, CapabilityDescriptor> byReceipt = new LinkedHashMap<>();
    private final Map<String, TargetBinding> targetBindings = new LinkedHashMap<>();
    private final Map<String, HostToolBinding> hostBindings = new ConcurrentHashMap<>();

    private record TargetBinding(CapabilityMetadata.TargetSource source,
            String argument, String fixed) { }

    /** Bind only the exact host inventory; plugins cannot register trusted evidence. */
    public HostToolBinding bindHostTool(ToolDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (!byId.values().stream().anyMatch(capability ->
                capability.trustedTools().contains(descriptor.name()))
                || !ToolRiskRegistry.matchesHostContract(descriptor)) {
            throw new IllegalArgumentException("tool is not a matching host capability: "
                    + descriptor.name());
        }
        ToolRiskLevel level = ToolRiskRegistry.levelOf(descriptor.name());
        String risk = level == null && ToolRiskRegistry.isKnownHostReadOnly(descriptor.name())
                ? "READ_ONLY" : level == null ? "NO_CONFIRMATION_POLICY" : level.name();
        HostToolBinding binding = new HostToolBinding(descriptor.name(),
                descriptor.inputSchema(), toolResultSchema(), descriptor.requiredPermissions(),
                risk, descriptor.idempotent(), descriptor.effectPolicy());
        HostToolBinding prior = hostBindings.putIfAbsent(descriptor.name(), binding);
        if (prior != null && !prior.equals(binding)) {
            throw new IllegalStateException("host tool contract changed during runtime: "
                    + descriptor.name());
        }
        return prior == null ? binding : prior;
    }

    public Optional<HostToolBinding> hostBinding(String tool) {
        return Optional.ofNullable(hostBindings.get(tool));
    }

    public TrustedCapabilityRegistry registerHost(CapabilityDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (byId.putIfAbsent(descriptor.id(), descriptor) != null)
            throw new IllegalArgumentException("duplicate capability ID: " + descriptor.id());
        for (String tool : descriptor.trustedTools()) {
            String key = tool + '\u0000' + descriptor.operation();
            if (byReceipt.putIfAbsent(key, descriptor) != null)
                throw new IllegalArgumentException("duplicate tool operation: " + tool);
        }
        return this;
    }

    public Optional<CapabilityDescriptor> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<CapabilityDescriptor> forReceipt(String tool, String operation) {
        return Optional.ofNullable(byReceipt.get(tool + '\u0000' + operation));
    }

    /** Exact tool lookup; ambiguous tool-to-capability mappings cannot compile a static claim. */
    public Optional<CapabilityMetadata> metadataForTool(String tool) {
        List<CapabilityDescriptor> matches = byId.values().stream()
                .filter(descriptor -> descriptor.trustedTools().contains(tool)).toList();
        if (matches.size() != 1) return Optional.empty();
        CapabilityDescriptor descriptor = matches.getFirst();
        TargetBinding binding = targetBindings.getOrDefault(tool,
                new TargetBinding(CapabilityMetadata.TargetSource.DECLARED, "", ""));
        return Optional.of(new CapabilityMetadata(descriptor.id(), descriptor.operation(),
                descriptor.evidenceCeiling(), metadataTargetKind(descriptor.targetKind()),
                binding.source(), binding.argument(), binding.fixed()));
    }

    private TrustedCapabilityRegistry targetArgument(String argument, String... tools) {
        for (String tool : tools) {
            if (!hasTrustedTool(tool) || targetBindings.putIfAbsent(tool,
                    new TargetBinding(CapabilityMetadata.TargetSource.ARGUMENT,
                            argument, "")) != null) {
                throw new IllegalArgumentException("invalid host target argument binding: " + tool);
            }
        }
        return this;
    }

    private TrustedCapabilityRegistry fixedTarget(String target, String... tools) {
        for (String tool : tools) {
            if (!hasTrustedTool(tool) || targetBindings.putIfAbsent(tool,
                    new TargetBinding(CapabilityMetadata.TargetSource.FIXED,
                            "", target)) != null) {
                throw new IllegalArgumentException("invalid host fixed target binding: " + tool);
            }
        }
        return this;
    }

    private static CapabilityMetadata.TargetKind metadataTargetKind(TargetKind kind) {
        return switch (kind) {
            case FILE -> CapabilityMetadata.TargetKind.FILE;
            case URL -> CapabilityMetadata.TargetKind.URL;
            case DESKTOP_APPLICATION -> CapabilityMetadata.TargetKind.DESKTOP_APPLICATION;
            case EMAIL_ADDRESS -> CapabilityMetadata.TargetKind.EMAIL_ADDRESS;
            case SCHEDULE -> CapabilityMetadata.TargetKind.SCHEDULE;
            case COMMAND -> CapabilityMetadata.TargetKind.COMMAND;
            case RESOURCE -> CapabilityMetadata.TargetKind.RESOURCE;
        };
    }

    public boolean hasTrustedTool(String tool) {
        return byId.values().stream().anyMatch(capability ->
                capability.trustedTools().contains(tool));
    }

    public boolean supports(TaskCriterionV3 criterion) {
        return find(criterion.capabilityId()).filter(descriptor ->
                criterion.targetType() == metadataTargetKind(descriptor.targetKind())
                        && rank(criterion.requiredEvidence())
                                <= rank(descriptor.evidenceCeiling()))
                .isPresent();
    }

    public ArrayNode planningCatalog() {
        ArrayNode values = JsonNodeFactory.instance.arrayNode();
        for (CapabilityDescriptor descriptor : byId.values()) {
            var value = values.addObject().put("id", descriptor.id())
                    .put("description", descriptor.description())
                    .put("targetKind", descriptor.targetKind().name())
                    .put("evidenceCeiling", descriptor.evidenceCeiling().name())
                    .put("verifierPolicy", descriptor.verifierPolicy().name());
            descriptor.trustedTools().stream().sorted().forEach(value.putArray("trustedTools")::add);
        }
        return values;
    }

    public boolean targetMatches(CapabilityDescriptor descriptor, String expected, String actual) {
        if (expected == null || actual == null || expected.isBlank() || actual.isBlank()) return false;
        return switch (descriptor.targetKind()) {
            case FILE -> samePath(expected, actual);
            case URL -> sameUrl(expected, actual);
            case DESKTOP_APPLICATION, EMAIL_ADDRESS -> expected.strip().equalsIgnoreCase(actual.strip());
            case SCHEDULE, COMMAND, RESOURCE -> expected.strip().equals(actual.strip());
        };
    }

    public static int rank(EffectReceiptV1.Status status) {
        return switch (status) {
            case ACCEPTED -> 1;
            case OBSERVED -> 2;
            case VERIFIED -> 3;
            case FAILED, UNKNOWN -> 0;
        };
    }

    private static VerifierPolicy policyFor(TargetKind kind) {
        return switch (kind) {
            case FILE -> VerifierPolicy.FILE_POSTCONDITION;
            case DESKTOP_APPLICATION -> VerifierPolicy.DESKTOP_LINKED_FRAME;
            case EMAIL_ADDRESS -> VerifierPolicy.TRANSPORT_ACCEPTANCE;
            case SCHEDULE -> VerifierPolicy.SCHEDULE_STATE;
            case COMMAND -> VerifierPolicy.PROCESS_EXIT_ONLY;
            case URL, RESOURCE -> VerifierPolicy.EXACT_HOST_RECEIPT;
        };
    }

    private static JsonNode toolResultSchema() {
        var schema = JsonNodeFactory.instance.objectNode().put("type", "object");
        var properties = schema.putObject("properties");
        properties.putObject("status").put("type", "string")
                .putArray("enum").add("SUCCEEDED").add("FAILED").add("TIMED_OUT")
                .add("PENDING").add("UNCERTAIN").add("REOBSERVE").add("UNKNOWN");
        properties.putObject("data");
        properties.putObject("errorCode").put("type", "string");
        properties.putObject("displayMessage").put("type", "string");
        schema.putArray("required").add("status").add("data")
                .add("errorCode").add("displayMessage");
        schema.put("additionalProperties", false);
        return schema;
    }

    private static boolean samePath(String expected, String actual) {
        try { return Path.of(expected).toAbsolutePath().normalize()
                .equals(Path.of(actual).toAbsolutePath().normalize()); }
        catch (RuntimeException invalid) { return false; }
    }

    private static boolean sameUrl(String expected, String actual) {
        try {
            URI observed = URI.create(actual).normalize();
            if (observed.getHost() == null) return false;
            URI requested = URI.create(expected).normalize();
            if (requested.getHost() == null) {
                return expected.indexOf('/') < 0 && expected.indexOf(' ') < 0
                        && expected.equalsIgnoreCase(observed.getHost());
            }
            String a = requested.getScheme() == null ? "" : requested.getScheme().toLowerCase(Locale.ROOT);
            String b = observed.getScheme() == null ? "" : observed.getScheme().toLowerCase(Locale.ROOT);
            return a.equals(b) && requested.getHost().equalsIgnoreCase(observed.getHost())
                    && requested.getPort() == observed.getPort()
                    && Objects.equals(requested.getRawPath(), observed.getRawPath())
                    && Objects.equals(requested.getRawQuery(), observed.getRawQuery());
        } catch (RuntimeException invalid) { return false; }
    }

    private static String required(String value) {
        value = Objects.requireNonNull(value, "value").strip();
        if (value.isEmpty()) throw new IllegalArgumentException("blank capability value");
        return value;
    }

    private TrustedCapabilityRegistry add(String id, String description, String operation,
            TargetKind target, EffectReceiptV1.Status ceiling, String... tools) {
        return registerHost(new CapabilityDescriptor(id, description, operation,
                target, ceiling, Set.of(tools)));
    }

    public static TrustedCapabilityRegistry builtins() {
        TrustedCapabilityRegistry catalog = new TrustedCapabilityRegistry();
        var A = EffectReceiptV1.Status.ACCEPTED;
        var O = EffectReceiptV1.Status.OBSERVED;
        var V = EffectReceiptV1.Status.VERIFIED;
        catalog.add("file.write", "Write a project file", "write", TargetKind.FILE, V, "sys_file_write")
                .add("file.read", "Read a project file", "read", TargetKind.FILE, O, "sys_file_read")
                .add("file.list", "List a project directory", "list", TargetKind.FILE, O, "sys_file_list")
                .add("file.delete", "Delete a project path", "delete", TargetKind.FILE, V, "sys_file_delete")
                .add("file.copy", "Copy a project path", "copy", TargetKind.FILE, V, "sys_file_copy")
                .add("file.move", "Move a project path", "move", TargetKind.FILE, V, "sys_file_move")
                .add("file.mkdir", "Create a project directory", "mkdir", TargetKind.FILE, V, "sys_file_mkdir")
                .add("desktop.probe", "Read current desktop provider capability/permission probe; "
                                + "target=desktop, requiredSubject empty. A returned available=false is "
                                + "an observed blocker, never proof of access or control.",
                        "probe", TargetKind.RESOURCE, O, "desktop_session_probe")
                .add("desktop.applications", "Read a native installed-application catalog page; "
                                + "target=desktop, requiredSubject is the exact requested query (empty "
                                + "only for an unfiltered catalog). Preserve pagination and truncated "
                                + "flags; a page proves neither unique identity, absence, a window nor control.",
                        "applications", TargetKind.RESOURCE, O, "desktop_session_applications")
                .add("desktop.targets", "Read the actual desktop window target list; target=desktop, "
                                + "requiredSubject empty. Window titles are untrusted labels, not ownership "
                                + "or observed application content.",
                        "targets", TargetKind.RESOURCE, O, "desktop_session_targets")
                .add("desktop.launch", "Launch a desktop application", "launch_application",
                        TargetKind.DESKTOP_APPLICATION, A, "desktop_session_launch_application")
                .add("desktop.open", "Establish an owned desktop window session", "open",
                        TargetKind.DESKTOP_APPLICATION, A, "desktop_session_open")
                .add("desktop.observe", "Observe an owned desktop window", "observe",
                        TargetKind.DESKTOP_APPLICATION, O, "desktop_session_observe")
                .add("desktop.snapshot", "Capture an owned desktop window", "snapshot",
                        TargetKind.DESKTOP_APPLICATION, O, "desktop_session_snapshot")
                .add("desktop.click", "Click an observed desktop target; requiredSubject must be empty because the input receipt has no logical button subject. Keep the requested button in the criterion description and verify the outcome with an independent desktop.observe criterion", "click",
                        TargetKind.DESKTOP_APPLICATION, A, "desktop_session_click")
                .add("desktop.type", "Type into an observed desktop target; requiredSubject must be empty because the input receipt has no logical content subject. Keep the requested text in the criterion description and verify the outcome with an independent desktop.observe criterion", "type",
                        TargetKind.DESKTOP_APPLICATION, A, "desktop_session_type")
                .add("desktop.key", "Send a key to an observed desktop target; requiredSubject must be empty because the input receipt has no logical key subject. Keep the requested key in the criterion description and verify the outcome with an independent desktop.observe criterion", "key",
                        TargetKind.DESKTOP_APPLICATION, A, "desktop_session_key")
                .add("desktop.scroll", "Scroll an observed desktop target; requiredSubject must be empty because the input receipt has no logical scroll subject. Keep the requested scroll in the criterion description and verify the outcome with an independent desktop.observe criterion", "scroll",
                        TargetKind.DESKTOP_APPLICATION, A, "desktop_session_scroll")
                .add("browser.observe", "Observe actual browser-page content. The target may be an "
                                + "exact full URL, preserving its query, or an exact bare host when "
                                + "the requested website is known but the final result URL is not. "
                                + "A nonempty requiredSubject is a short literal observed text fragment "
                                + "of at most 128 characters. requiredTextFragments supports up to "
                                + "8 independent exact human text fragments, each at most 128 "
                                + "characters and at most 256 total, all in the same body observation; "
                                + "do not join separated words into a nonexistent contiguous subject. "
                                + "These prove literal text only; preserve the complete business "
                                + "result in the criterion description and answer. "
                                + "A search query echoed by the title, URL "
                                + "or input field does not prove the requested result content.",
                        "observe", TargetKind.URL, O,
                        "web_get_title", "web_get_url", "web_get_text", "web_get_html",
                        "web_get_attribute", "web_get_count", "web_get_value", "web_is_checked",
                        "web_is_enabled", "web_is_visible", "web_snapshot", "web_screenshot",
                        "web_screenshot_annotated", "web_wait_for_element", "web_wait_for_load",
                        "web_wait_for_text", "web_wait_for_url")
                .add("browser.navigate", "Navigate a browser page; requiredSubject must be empty. "
                                + "Navigation admission does not prove page content. Use a separate "
                                + "browser.observe criterion for a requested lookup result. Do not "
                                + "require a homepage visit when it is only an optional path to that result. "
                                + "Planning input criteria must declare intentBasis and humanQuote: "
                                + "only a directly human-requested action is mandatory; a chosen "
                                + "interaction path is IMPLEMENTATION_CHOICE.",
                        "navigate", TargetKind.URL, A,
                        "web_navigate")
                .add("browser.click", "Click a browser page" + BROWSER_PAGE_INPUT_TARGET, "click", TargetKind.URL, A,
                        "web_click")
                .add("browser.double_click", "Double click a browser page" + BROWSER_PAGE_INPUT_TARGET, "dblclick", TargetKind.URL, A,
                        "web_dblclick")
                .add("browser.fill", "Fill a browser field" + BROWSER_PAGE_INPUT_TARGET, "fill", TargetKind.URL, A,
                        "web_fill")
                .add("browser.select", "Select a browser option" + BROWSER_PAGE_INPUT_TARGET, "select", TargetKind.URL, A,
                        "web_select")
                .add("browser.check", "Check a browser control" + BROWSER_PAGE_INPUT_TARGET, "check", TargetKind.URL, A,
                        "web_check")
                .add("browser.upload", "Upload through a browser page" + BROWSER_PAGE_INPUT_TARGET, "upload", TargetKind.URL, A,
                        "web_upload")
                .add("browser.type", "Type into a browser page" + BROWSER_PAGE_INPUT_TARGET, "type", TargetKind.URL, A,
                        "web_type")
                .add("browser.press_key", "Press a browser key" + BROWSER_PAGE_INPUT_TARGET, "press_key", TargetKind.URL, A,
                        "web_press_key")
                .add("browser.drag", "Drag on a browser page" + BROWSER_PAGE_INPUT_TARGET, "drag", TargetKind.URL, A,
                        "web_drag")
                .add("browser.hover", "Hover over a browser target" + BROWSER_PAGE_INPUT_TARGET, "hover", TargetKind.URL, A,
                        "web_hover")
                .add("browser.scroll", "Scroll a browser page" + BROWSER_PAGE_INPUT_TARGET, "scroll", TargetKind.URL, A,
                        "web_scroll")
                .add("browser.tab_new", "Open a browser tab", "tab_new", TargetKind.URL, A,
                        "web_tab_new")
                .add("email.send", "Submit an email to transport", "send", TargetKind.EMAIL_ADDRESS,
                        A, "email_send", "email_send_with_cc", "email_reply")
                .add("email.observe", "Read a mailbox", "observe", TargetKind.RESOURCE,
                        O, "email_list_inbox", "email_list_unread", "email_read", "email_search")
                .add("notification.send", "Submit a notification to a configured transport",
                        "send", TargetKind.RESOURCE, A, "notify_send", "notify_dingtalk",
                        "notify_wechat", "notify_feishu", "notify_email", "notify_custom_webhook")
                .add("notification.observe", "Read notification channel configuration",
                        "observe", TargetKind.RESOURCE, O, "notify_list_channels")
                .add("mcp.secure_input.cancel", "Observe cancellation or no valid value from a local "
                                + "secure Header input for an existing HTTP MCP server; target is the exact "
                                + "server name, requiredSubject is the exact Header name. This proves only "
                                + "input_cancelled, with no configuration save or reconnect; it never proves "
                                + "that a Header was set, a WRITE succeeded or a connection was established.",
                        "input_cancelled", TargetKind.RESOURCE, O, "mcp_server_set_header_secure")
                .add("schedule.create", "Create an enabled schedule", "create", TargetKind.SCHEDULE,
                        V, "schedule_create")
                .add("schedule.observe", "Read a schedule", "observe", TargetKind.SCHEDULE,
                        O, "schedule_get", "schedule_list")
                .add("schedule.run", "Start a scheduled task", "run", TargetKind.SCHEDULE,
                        A, "schedule_run_now")
                .add("schedule.disable", "Disable a schedule", "disable", TargetKind.SCHEDULE,
                        V, "schedule_disable")
                .add("schedule.delete", "Delete a schedule", "delete", TargetKind.SCHEDULE,
                        V, "schedule_delete")
                .add("command.execute", "Run a command process; external effects remain unverified",
                        "execute", TargetKind.COMMAND, A, "cmd_execute", "cmd_session_exec");
        catalog.targetArgument("path", "sys_file_read", "sys_file_list", "sys_file_write",
                        "sys_file_delete", "sys_file_mkdir")
                .targetArgument("target", "sys_file_copy", "sys_file_move")
                .targetArgument("application", "desktop_session_launch_application")
                .fixedTarget("desktop", "desktop_session_probe", "desktop_session_applications",
                        "desktop_session_targets")
                .targetArgument("url", "web_navigate")
                .targetArgument("to", "email_send", "email_send_with_cc")
                .fixedTarget("inbox", "email_list_inbox", "email_list_unread",
                        "email_read", "email_search")
                .fixedTarget("all", "notify_send")
                .fixedTarget("dingtalk", "notify_dingtalk")
                .fixedTarget("wechat", "notify_wechat")
                .fixedTarget("feishu", "notify_feishu")
                .fixedTarget("email", "notify_email")
                .fixedTarget("custom", "notify_custom_webhook")
                .fixedTarget("notification", "notify_list_channels")
                .targetArgument("name", "mcp_server_set_header_secure")
                .targetArgument("name", "schedule_create")
                .targetArgument("id", "schedule_get", "schedule_run_now",
                        "schedule_disable", "schedule_delete")
                .fixedTarget("schedule", "schedule_list")
                .fixedTarget("command", "cmd_execute", "cmd_session_exec");
        return catalog;
    }
}
