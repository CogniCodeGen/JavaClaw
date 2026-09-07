package com.javaclaw.protocol;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.javaclaw.api.CanonicalPayload;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewCommandBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewSchema;
import com.javaclaw.extension.spi.ViewSelectionMode;

/** ViewSchema v2 的显式 wire codec；每个 sealed 节点都携带稳定 {@code type}，未知类型直接拒绝。 */
public final class ViewSchemaWireCodec {
    private final CanonicalJson json;
    private final ViewFormFieldWireCodec formFields;

    /**
     * 创建 codec。
     *
     * @param json Protocol v3 规范 JSON codec
     */
    public ViewSchemaWireCodec(CanonicalJson json) {
        this.json = Objects.requireNonNull(json, "json");
        this.formFields = new ViewFormFieldWireCodec(json);
    }

    /**
     * 编码页面。
     *
     * @param schema 强类型页面
     * @return 带节点判别字段的规范 payload
     */
    public CanonicalPayload encode(ViewSchema schema) {
        Objects.requireNonNull(schema, "schema");
        List<CanonicalPayload> nodes =
                schema.nodes().stream().map(this::encodeNode).toList();
        return json.encode(
                new WireView(schema.schemaVersion(), schema.viewId(), schema.title(), schema.dataSources(), nodes));
    }

    /**
     * 解码 v2 页面并拒绝旧版本和未知节点类型。
     *
     * @param payload Protocol v3 页面 payload
     * @return 强类型页面
     */
    public ViewSchema decode(CanonicalPayload payload) {
        WireView wire = json.decode(payload, WireView.class);
        List<ViewSchema.Node> nodes =
                wire.nodes().stream().map(this::decodeNode).toList();
        return new ViewSchema(wire.schemaVersion(), wire.viewId(), wire.title(), wire.dataSources(), nodes);
    }

    private CanonicalPayload encodeNode(ViewSchema.Node node) {
        return switch (node) {
            case ViewSchema.Form form -> encodeForm(form);
            case ViewSchema.ListView list -> encodeList(list);
            case ViewSchema.Table table -> encodeTable(table);
            case ViewSchema.Card card -> encodeCard(card);
            case ViewSchema.Progress progress -> encodeProgress(progress);
            case ViewSchema.Timeline timeline -> encodeTimeline(timeline);
            case ViewSchema.Markdown markdown -> encodeMarkdown(markdown);
            case ViewSchema.Code code -> encodeCode(code);
            case ViewSchema.Artifact artifact -> encodeArtifact(artifact);
            case ViewSchema.Graph graph -> encodeGraph(graph);
        };
    }

    private CanonicalPayload encodeForm(ViewSchema.Form form) {
        return json.encode(new WireForm(
                "form", form.id(), form.title(), formFields.encodeAll(form.fields()), wireAction(form.submit())));
    }

    private CanonicalPayload encodeList(ViewSchema.ListView list) {
        return json.encode(new WireList(
                "list",
                list.id(),
                list.title(),
                list.sourceId(),
                list.keyField(),
                list.titleField(),
                list.detailField(),
                list.selection(),
                wireActions(list.actions())));
    }

    private CanonicalPayload encodeTable(ViewSchema.Table table) {
        return json.encode(new WireTable(
                "table",
                table.id(),
                table.title(),
                table.sourceId(),
                table.keyField(),
                table.columns(),
                table.selection(),
                wireActions(table.actions())));
    }

    private CanonicalPayload encodeCard(ViewSchema.Card card) {
        return json.encode(new WireCard("card", card.id(), card.title(), card.body(), wireActions(card.actions())));
    }

    private CanonicalPayload encodeProgress(ViewSchema.Progress progress) {
        return json.encode(new WireProgress(
                "progress",
                progress.id(),
                progress.title(),
                progress.sourceId(),
                progress.valueField(),
                progress.labelField()));
    }

    private CanonicalPayload encodeTimeline(ViewSchema.Timeline timeline) {
        return json.encode(new WireTimeline(
                "timeline",
                timeline.id(),
                timeline.title(),
                timeline.sourceId(),
                timeline.timeField(),
                timeline.contentField()));
    }

    private CanonicalPayload encodeMarkdown(ViewSchema.Markdown markdown) {
        return json.encode(new WireMarkdown("markdown", markdown.id(), markdown.title(), markdown.source()));
    }

    private CanonicalPayload encodeCode(ViewSchema.Code code) {
        return json.encode(new WireCode("code", code.id(), code.title(), code.source(), code.language()));
    }

    private CanonicalPayload encodeArtifact(ViewSchema.Artifact artifact) {
        return json.encode(new WireArtifact(
                "artifact", artifact.id(), artifact.title(), artifact.reference(), artifact.mediaType()));
    }

    private CanonicalPayload encodeGraph(ViewSchema.Graph graph) {
        return json.encode(new WireGraph(
                "graph",
                graph.id(),
                graph.title(),
                graph.nodeSourceId(),
                graph.edgeSourceId(),
                graph.nodeIdField(),
                graph.nodeLabelField(),
                graph.nodeKindField(),
                graph.edgeFromField(),
                graph.edgeToField()));
    }

    private ViewSchema.Node decodeNode(CanonicalPayload payload) {
        String type = json.textField(payload, "type")
                .orElseThrow(
                        () -> new ProtocolException(ProtocolErrorCode.INVALID_PARAMS, "view node type is required"));
        return switch (type) {
            case "form" -> form(json.decode(payload, WireForm.class));
            case "list" -> list(json.decode(payload, WireList.class));
            case "table" -> table(json.decode(payload, WireTable.class));
            case "card" -> card(json.decode(payload, WireCard.class));
            case "progress" -> progress(json.decode(payload, WireProgress.class));
            case "timeline" -> timeline(json.decode(payload, WireTimeline.class));
            case "markdown" -> markdown(json.decode(payload, WireMarkdown.class));
            case "code" -> code(json.decode(payload, WireCode.class));
            case "artifact" -> artifact(json.decode(payload, WireArtifact.class));
            case "graph" -> graph(json.decode(payload, WireGraph.class));
            default -> throw new ProtocolException(ProtocolErrorCode.INVALID_PARAMS, "unknown view node type: " + type);
        };
    }

    private ViewSchema.Form form(WireForm wire) {
        return new ViewSchema.Form(wire.id(), wire.title(), formFields.decodeAll(wire.fields()), action(wire.submit()));
    }

    private static ViewSchema.ListView list(WireList wire) {
        return new ViewSchema.ListView(
                wire.id(),
                wire.title(),
                wire.sourceId(),
                wire.keyField(),
                wire.titleField(),
                wire.detailField(),
                wire.selection(),
                actions(wire.actions()));
    }

    private static ViewSchema.Table table(WireTable wire) {
        return new ViewSchema.Table(
                wire.id(),
                wire.title(),
                wire.sourceId(),
                wire.keyField(),
                wire.columns(),
                wire.selection(),
                actions(wire.actions()));
    }

    private static ViewSchema.Card card(WireCard wire) {
        return new ViewSchema.Card(wire.id(), wire.title(), wire.body(), actions(wire.actions()));
    }

    private static ViewSchema.Progress progress(WireProgress wire) {
        return new ViewSchema.Progress(wire.id(), wire.title(), wire.sourceId(), wire.valueField(), wire.labelField());
    }

    private static ViewSchema.Timeline timeline(WireTimeline wire) {
        return new ViewSchema.Timeline(wire.id(), wire.title(), wire.sourceId(), wire.timeField(), wire.contentField());
    }

    private static ViewSchema.Markdown markdown(WireMarkdown wire) {
        return new ViewSchema.Markdown(wire.id(), wire.title(), wire.source());
    }

    private static ViewSchema.Code code(WireCode wire) {
        return new ViewSchema.Code(wire.id(), wire.title(), wire.source(), wire.language());
    }

    private static ViewSchema.Artifact artifact(WireArtifact wire) {
        return new ViewSchema.Artifact(wire.id(), wire.title(), wire.reference(), wire.mediaType());
    }

    private static ViewSchema.Graph graph(WireGraph wire) {
        return new ViewSchema.Graph(
                wire.id(),
                wire.title(),
                wire.nodeSourceId(),
                wire.edgeSourceId(),
                wire.nodeIdField(),
                wire.nodeLabelField(),
                wire.nodeKindField(),
                wire.edgeFromField(),
                wire.edgeToField());
    }

    private static List<WireAction> wireActions(List<ViewAction> actions) {
        return actions.stream().map(ViewSchemaWireCodec::wireAction).toList();
    }

    private static WireAction wireAction(ViewAction action) {
        WireExpectedRevision expected =
                switch (action.expectedRevision()) {
                    case ExpectedRevisionBinding.None ignored ->
                        new WireExpectedRevision("none", Optional.empty(), Optional.empty());
                    case ExpectedRevisionBinding.SourceRevision source ->
                        new WireExpectedRevision("source", Optional.of(source.sourceId()), Optional.empty());
                    case ExpectedRevisionBinding.RowField row ->
                        new WireExpectedRevision("row", Optional.empty(), Optional.of(row.field()));
                };
        return new WireAction(
                action.label(),
                action.command(),
                action.arguments(),
                action.rowArguments(),
                action.commandBindings(),
                expected,
                action.dangerous());
    }

    private static List<ViewAction> actions(List<WireAction> actions) {
        return actions.stream().map(ViewSchemaWireCodec::action).toList();
    }

    private static ViewAction action(WireAction wire) {
        try {
            require(wire.commandBindings() != null, "commandBindings are required");
            require(wire.commandBindings().size() <= ViewAction.MAX_COMMAND_BINDINGS, "too many commandBindings");
            ExpectedRevisionBinding expected = expectedRevision(wire.expectedRevision());
            return new ViewAction(
                    wire.label(),
                    wire.command(),
                    wire.arguments(),
                    wire.rowArguments(),
                    expected,
                    wire.dangerous(),
                    wire.commandBindings().toArray(ViewCommandBinding[]::new));
        } catch (ProtocolException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid("view action violates the bounded contract");
        }
    }

    private static ExpectedRevisionBinding expectedRevision(WireExpectedRevision wire) {
        return switch (wire.type()) {
            case "none" -> {
                require(wire.sourceId().isEmpty() && wire.field().isEmpty(), "none revision contains fields");
                yield new ExpectedRevisionBinding.None();
            }
            case "source" -> {
                require(wire.sourceId().isPresent() && wire.field().isEmpty(), "source revision fields are invalid");
                yield new ExpectedRevisionBinding.SourceRevision(wire.sourceId().orElseThrow());
            }
            case "row" -> {
                require(wire.sourceId().isEmpty() && wire.field().isPresent(), "row revision fields are invalid");
                yield new ExpectedRevisionBinding.RowField(wire.field().orElseThrow());
            }
            default -> throw invalid("unknown expected revision binding");
        };
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw invalid(message);
        }
    }

    private static ProtocolException invalid(String message) {
        return new ProtocolException(ProtocolErrorCode.INVALID_PARAMS, message);
    }

    private record WireView(
            int schemaVersion,
            String viewId,
            String title,
            List<ViewDataSource> dataSources,
            List<CanonicalPayload> nodes) {}

    private record WireForm(String type, String id, String title, List<CanonicalPayload> fields, WireAction submit) {}

    private record WireList(
            String type,
            String id,
            String title,
            String sourceId,
            String keyField,
            String titleField,
            String detailField,
            ViewSelectionMode selection,
            List<WireAction> actions) {}

    private record WireTable(
            String type,
            String id,
            String title,
            String sourceId,
            String keyField,
            List<ViewSchema.Column> columns,
            ViewSelectionMode selection,
            List<WireAction> actions) {}

    private record WireCard(String type, String id, String title, String body, List<WireAction> actions) {}

    private record WireAction(
            String label,
            String command,
            java.util.Map<String, String> arguments,
            java.util.Map<String, String> rowArguments,
            List<ViewCommandBinding> commandBindings,
            WireExpectedRevision expectedRevision,
            boolean dangerous) {}

    private record WireExpectedRevision(String type, Optional<String> sourceId, Optional<String> field) {}

    private record WireProgress(
            String type, String id, String title, String sourceId, String valueField, String labelField) {}

    private record WireTimeline(
            String type, String id, String title, String sourceId, String timeField, String contentField) {}

    private record WireMarkdown(String type, String id, String title, ViewBinding source) {}

    private record WireCode(String type, String id, String title, ViewBinding source, Optional<ViewBinding> language) {}

    private record WireArtifact(String type, String id, String title, ViewBinding reference, ViewBinding mediaType) {}

    private record WireGraph(
            String type,
            String id,
            String title,
            String nodeSourceId,
            String edgeSourceId,
            String nodeIdField,
            String nodeLabelField,
            String nodeKindField,
            String edgeFromField,
            String edgeToField) {}
}
