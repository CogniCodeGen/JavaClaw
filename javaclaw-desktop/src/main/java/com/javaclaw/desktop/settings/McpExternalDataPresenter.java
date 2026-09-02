package com.javaclaw.desktop.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import com.javaclaw.api.McpEndpoint;
import com.javaclaw.api.McpPromptDescriptor;
import com.javaclaw.api.McpPromptPage;
import com.javaclaw.api.McpPromptResult;
import com.javaclaw.api.McpResourceDescriptor;
import com.javaclaw.api.McpResourcePage;
import com.javaclaw.api.McpResourceReadResult;

/** MCP Resource 与 Prompt 的显式读取状态机；外部内容不写入 Turn 或 Prompt。 */
final class McpExternalDataPresenter {
    private final McpSettingsGateway gateway;
    private Consumer<McpExternalDataState> listener = ignored -> {};
    private McpExternalDataState state = McpExternalDataState.initial();

    McpExternalDataPresenter(McpSettingsGateway gateway) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
    }

    void subscribe(Consumer<McpExternalDataState> value) {
        listener = Objects.requireNonNull(value, "listener");
        listener.accept(state);
    }

    void select(Optional<McpEndpoint> endpoint) {
        Optional<McpEndpoint> checked = Objects.requireNonNull(endpoint, "endpoint");
        String current = state.endpoint()
                .map(value -> value.id() + ':' + value.revision())
                .orElse("");
        String next = checked.map(value -> value.id() + ':' + value.revision()).orElse("");
        if (current.equals(next)) {
            return;
        }
        publish(new McpExternalDataState(
                checked,
                SettingsLoadState.READY,
                List.of(),
                Optional.empty(),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                checked.isPresent() ? "可显式读取外部 Resource 或 Prompt" : "请选择已启用的 HTTPS Endpoint",
                state.epoch() + 1));
    }

    void loadResources(boolean nextPage) {
        McpEndpoint endpoint = requireEndpoint();
        Optional<String> cursor = nextPage ? state.resourceCursor() : Optional.empty();
        long epoch = begin("正在读取外部 Resource…");
        gateway.mcpResources(endpoint.id(), cursor)
                .whenComplete((page, failure) -> completeResources(epoch, page, failure, nextPage));
    }

    void readResource(McpResourceDescriptor resource) {
        McpEndpoint endpoint = requireEndpoint();
        long epoch = begin("正在显式读取 Resource…");
        gateway.readMcpResource(
                        endpoint.id(),
                        Objects.requireNonNull(resource, "resource").uri())
                .whenComplete((result, failure) -> completeResource(epoch, result, failure));
    }

    void loadPrompts(boolean nextPage) {
        McpEndpoint endpoint = requireEndpoint();
        Optional<String> cursor = nextPage ? state.promptCursor() : Optional.empty();
        long epoch = begin("正在读取外部 Prompt…");
        gateway.mcpPrompts(endpoint.id(), cursor)
                .whenComplete((page, failure) -> completePrompts(epoch, page, failure, nextPage));
    }

    void getPrompt(McpPromptDescriptor prompt, Map<String, String> arguments) {
        McpEndpoint endpoint = requireEndpoint();
        long epoch = begin("正在显式展开 Prompt…");
        gateway.getMcpPrompt(
                        endpoint.id(), Objects.requireNonNull(prompt, "prompt").name(), arguments)
                .whenComplete((result, failure) -> completePrompt(epoch, result, failure));
    }

    private void completeResources(long epoch, McpResourcePage page, Throwable failure, boolean append) {
        if (stale(epoch) || failed(epoch, failure)) {
            return;
        }
        List<McpResourceDescriptor> resources = append ? append(state.resources(), page.resources()) : page.resources();
        Data data = new Data(
                resources, page.nextCursor(), state.prompts(), state.promptCursor(), Optional.empty(), state.prompt());
        publish(copy(SettingsLoadState.READY, data, "Resource 已按外部数据读取"));
    }

    private void completeResource(long epoch, McpResourceReadResult result, Throwable failure) {
        if (stale(epoch) || failed(epoch, failure)) {
            return;
        }
        Data data = new Data(
                state.resources(),
                state.resourceCursor(),
                state.prompts(),
                state.promptCursor(),
                Optional.of(result),
                Optional.empty());
        publish(copy(SettingsLoadState.READY, data, "Resource 内容仅显示在当前管理页"));
    }

    private void completePrompts(long epoch, McpPromptPage page, Throwable failure, boolean append) {
        if (stale(epoch) || failed(epoch, failure)) {
            return;
        }
        List<McpPromptDescriptor> prompts = append ? append(state.prompts(), page.prompts()) : page.prompts();
        Data data = new Data(
                state.resources(),
                state.resourceCursor(),
                prompts,
                page.nextCursor(),
                state.resource(),
                Optional.empty());
        publish(copy(SettingsLoadState.READY, data, "Prompt 模板已按外部数据读取"));
    }

    private void completePrompt(long epoch, McpPromptResult result, Throwable failure) {
        if (stale(epoch) || failed(epoch, failure)) {
            return;
        }
        Data data = new Data(
                state.resources(),
                state.resourceCursor(),
                state.prompts(),
                state.promptCursor(),
                Optional.empty(),
                Optional.of(result));
        publish(copy(SettingsLoadState.READY, data, "Prompt 消息未进入 system context"));
    }

    private long begin(String message) {
        long epoch = state.epoch() + 1;
        publish(copy(SettingsLoadState.LOADING, currentData(), message, epoch));
        return epoch;
    }

    private boolean failed(long epoch, Throwable failure) {
        if (failure == null) {
            return false;
        }
        publish(copy(SettingsLoadState.ERROR, currentData(), message(failure), epoch));
        return true;
    }

    private McpEndpoint requireEndpoint() {
        return state.endpoint().orElseThrow(() -> new IllegalStateException("请选择 MCP Endpoint"));
    }

    private boolean stale(long epoch) {
        return state.epoch() != epoch;
    }

    private Data currentData() {
        return new Data(
                state.resources(),
                state.resourceCursor(),
                state.prompts(),
                state.promptCursor(),
                state.resource(),
                state.prompt());
    }

    private McpExternalDataState copy(SettingsLoadState phase, Data data, String message) {
        return copy(phase, data, message, state.epoch());
    }

    private McpExternalDataState copy(SettingsLoadState phase, Data data, String message, long epoch) {
        return new McpExternalDataState(
                state.endpoint(),
                phase,
                data.resources(),
                data.resourceCursor(),
                data.prompts(),
                data.promptCursor(),
                data.resource(),
                data.prompt(),
                message,
                epoch);
    }

    private void publish(McpExternalDataState value) {
        state = Objects.requireNonNull(value, "value");
        listener.accept(state);
    }

    private static <T> List<T> append(List<T> first, List<T> second) {
        ArrayList<T> values = new ArrayList<>(first);
        values.addAll(second);
        return List.copyOf(values);
    }

    private static String message(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record Data(
            List<McpResourceDescriptor> resources,
            Optional<String> resourceCursor,
            List<McpPromptDescriptor> prompts,
            Optional<String> promptCursor,
            Optional<McpResourceReadResult> resource,
            Optional<McpPromptResult> prompt) {}
}
