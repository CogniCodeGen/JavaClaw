package com.javaclaw.desktop;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.javaclaw.api.AgentTurn;
import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.api.ConversationThread;
import com.javaclaw.api.WorkspaceId;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.client.sdk.JavaClawClient;
import com.javaclaw.desktop.state.DesktopState;
import com.javaclaw.desktop.view.ViewCommandInvocation;
import com.javaclaw.desktop.view.ViewData;
import com.javaclaw.desktop.view.ViewLoadRequest;
import com.javaclaw.extension.spi.ViewArgumentBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewQueryRequest;
import com.javaclaw.extension.spi.ViewQueryResult;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.protocol.CanonicalJson;
import com.javaclaw.protocol.ExtensionRpcContracts;

/** 负责 ViewSchema 的查询与命令映射，不接触 JavaFX 控件或 Desktop 状态写入。 */
final class DesktopExtensionCoordinator {
    private final CanonicalJson json = new CanonicalJson();

    List<ExtensionRpcContracts.ViewDocument> views(JavaClawClient client, Optional<String> extensionId) {
        return client.extensions().views(Objects.requireNonNull(extensionId, "extensionId"));
    }

    ViewData load(
            JavaClawClient client,
            WorkspaceId workspaceId,
            ExtensionRpcContracts.ViewDocument document,
            ViewSchema schema,
            DesktopState snapshot,
            ViewLoadRequest loadRequest) {
        WorkspaceId checkedWorkspace = Objects.requireNonNull(workspaceId, "workspaceId");
        LinkedHashMap<String, ViewData.Source> sources = new LinkedHashMap<>();
        Map<String, String> keyFields = selectionKeyFields(schema);
        Set<String> masterSources = masterSources(schema);
        LoadScope scope = new LoadScope(
                client, document.extensionId(), checkedWorkspace, snapshot, loadRequest, keyFields, masterSources);
        PlatformViewDataSourceResolver platform = new PlatformViewDataSourceResolver(client, checkedWorkspace);
        for (ViewDataSource source : schema.dataSources()) {
            ViewData.Source loaded = platform.resolve(source).orElseGet(() -> querySource(scope, source, sources));
            sources.put(source.id(), loaded);
        }
        return new ViewData(sources);
    }

    ExtensionRpcContracts.CallResult execute(
            JavaClawClient client,
            WorkspaceId workspaceId,
            String extensionId,
            ViewCommandInvocation invocation,
            DesktopState snapshot) {
        WorkspaceId checkedWorkspace = Objects.requireNonNull(workspaceId, "workspaceId");
        Optional<ConversationThread> selectedThread = selectedThread(snapshot, checkedWorkspace);
        ExtensionRpcContracts.CallPayload call = new ExtensionRpcContracts.CallPayload(
                extensionId,
                checkedWorkspace,
                selectedThread.map(ConversationThread::id),
                selectedTurn(snapshot, selectedThread).map(AgentTurn::id),
                invocation.operation(),
                json.encode(invocation.arguments()));
        return client.extensions().command(call, CommandOptions.create(invocation.expectedRevision()));
    }

    private ViewData.Source querySource(LoadScope scope, ViewDataSource source, Map<String, ViewData.Source> loaded) {
        Optional<Map<String, String>> arguments = arguments(source, loaded, scope.keyFields());
        if (arguments.isEmpty()) {
            return ViewData.Source.empty();
        }
        ViewQueryRequest query = new ViewQueryRequest(
                source.id(),
                arguments.orElseThrow(),
                scope.request().cursor(source.id()),
                source.pageSize(),
                scope.request().selectedKey(source.id()));
        ExtensionRpcContracts.CallPayload call = new ExtensionRpcContracts.CallPayload(
                scope.extensionId(),
                scope.workspaceId(),
                selectedThread(scope.snapshot(), scope.workspaceId()).map(ConversationThread::id),
                selectedTurn(scope.snapshot(), selectedThread(scope.snapshot(), scope.workspaceId()))
                        .map(AgentTurn::id),
                source.query(),
                json.encode(query));
        ViewQueryResult result = scope.client().extensions().viewQuery(call);
        if (!source.id().equals(result.dataSourceId())) {
            throw new IllegalArgumentException("view query returned a different dataSourceId");
        }
        List<Map<String, Object>> rows =
                result.rows().stream().map(this::stringKeyMap).toList();
        Optional<String> selectedKey = selectedKey(
                source.id(), rows, scope.request().selectedKey(source.id()), scope.keyFields(), scope.masterSources());
        return new ViewData.Source(
                rows,
                stringKeyMap(result.values()),
                query.cursor(),
                result.nextCursor(),
                result.hasMore(),
                result.revision(),
                scope.request().pageIndex(source.id()),
                selectedKey);
    }

    private Optional<Map<String, String>> arguments(
            ViewDataSource source, Map<String, ViewData.Source> loaded, Map<String, String> keyFields) {
        LinkedHashMap<String, String> arguments = new LinkedHashMap<>(source.arguments());
        for (ViewArgumentBinding binding : source.argumentBindings()) {
            ViewData.Source master = loaded.get(binding.sourceId());
            String keyField = keyFields.get(binding.sourceId());
            if (master == null || keyField == null || master.selectedKey().isEmpty()) {
                return Optional.empty();
            }
            Optional<Map<String, Object>> row = master.rows().stream()
                    .filter(candidate -> master.selectedKey().orElseThrow().equals(text(candidate.get(keyField))))
                    .findFirst();
            if (row.isEmpty() || !row.orElseThrow().containsKey(binding.rowField())) {
                return Optional.empty();
            }
            Object value = row.orElseThrow().get(binding.rowField());
            if (value == null) {
                return Optional.empty();
            }
            arguments.put(binding.argument(), Objects.toString(value));
        }
        return Optional.of(Map.copyOf(arguments));
    }

    private Optional<String> selectedKey(
            String sourceId,
            List<Map<String, Object>> rows,
            Optional<String> requested,
            Map<String, String> keyFields,
            Set<String> masterSources) {
        if (!masterSources.contains(sourceId)) {
            return Optional.empty();
        }
        String keyField = keyFields.get(sourceId);
        if (keyField == null) {
            return Optional.empty();
        }
        if (requested.isPresent()
                && rows.stream().anyMatch(row -> requested.orElseThrow().equals(text(row.get(keyField))))) {
            return requested;
        }
        return rows.stream()
                .map(row -> text(row.get(keyField)))
                .filter(value -> !value.isBlank())
                .findFirst();
    }

    private Map<String, String> selectionKeyFields(ViewSchema schema) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        for (ViewSchema.Node node : schema.nodes()) {
            switch (node) {
                case ViewSchema.ListView list -> fields.putIfAbsent(list.sourceId(), list.keyField());
                case ViewSchema.Table table -> fields.putIfAbsent(table.sourceId(), table.keyField());
                default -> {
                    // 只有列表与表格具有选择键。
                }
            }
        }
        return Map.copyOf(fields);
    }

    private Set<String> masterSources(ViewSchema schema) {
        return schema.dataSources().stream()
                .flatMap(source -> source.argumentBindings().stream())
                .map(ViewArgumentBinding::sourceId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private String text(Object value) {
        return Objects.toString(value, "").strip();
    }

    private Map<String, Object> stringKeyMap(CanonicalPayload payload) {
        Map<?, ?> raw = json.decode(payload, Map.class);
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        raw.forEach((key, value) -> normalized.put(Objects.toString(key), value));
        return Map.copyOf(normalized);
    }

    private static Optional<ConversationThread> selectedThread(DesktopState snapshot, WorkspaceId workspaceId) {
        return snapshot.threads().selectedThread().filter(thread -> workspaceId.equals(thread.workspaceId()));
    }

    private static Optional<AgentTurn> selectedTurn(
            DesktopState snapshot, Optional<ConversationThread> selectedThread) {
        return snapshot.threads()
                .activeTurn()
                .filter(turn -> selectedThread
                        .map(thread -> thread.id().equals(turn.threadId()))
                        .orElse(false));
    }

    private record LoadScope(
            JavaClawClient client,
            String extensionId,
            WorkspaceId workspaceId,
            DesktopState snapshot,
            ViewLoadRequest request,
            Map<String, String> keyFields,
            Set<String> masterSources) {}
}
