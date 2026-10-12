package com.javaclaw.framework.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.javaclaw.framework.api.RunId;
import com.javaclaw.framework.api.RunRequest;
import java.util.List;

/** 内核持有宿主适配器的窄接口，不接触插件类或传输实现。 */
public interface ExternalPluginRuntime {
    Snapshot capture(RunRequest request);
    Snapshot restore(List<ExternalPluginLock> locks);

    interface Snapshot extends AutoCloseable {
        default List<ExternalPluginLock> locks() { return List.of(); }
        default List<FrameworkTool> tools(ToolContext context) { return List.of(); }
        default String prepare(RunRequest request, ExtensionStateView state) { return ""; }
        default RunConstraints restrict(RunConstraints current, RunRequest request) { return current; }
        default JsonNode observeModel(JsonNode output, RunRequest request, RunId runId) { return output; }
        default JsonNode toolResult(JsonNode view, ToolDescriptor tool,
                                    ToolExecutionContext context, RunRequest request) { return view; }
        @Override default void close() { }
    }

    ExternalPluginRuntime NONE = new ExternalPluginRuntime() {
        @Override public Snapshot capture(RunRequest request) { return new Snapshot() { }; }
        @Override public Snapshot restore(List<ExternalPluginLock> locks) {
            if (!locks.isEmpty()) throw new IllegalStateException("原插件运行时不可用，恢复已阻止");
            return new Snapshot() { };
        }
    };
}
