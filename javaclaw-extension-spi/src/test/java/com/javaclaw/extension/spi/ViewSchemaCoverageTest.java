package com.javaclaw.extension.spi;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.javaclaw.api.CanonicalPayload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewSchemaCoverageTest {
    @Test
    void passiveNodes覆盖列表卡片进度时间线代码和Artifact() {
        ViewBinding value = new ViewBinding("documents", "value");
        ViewAction action = simpleAction("打开", "open", false);
        List<ViewSchema.Node> nodes = List.of(
                new ViewSchema.ListView(
                        "list", "列表", "documents", "id", "title", "detail", ViewSelectionMode.SINGLE, List.of(action)),
                new ViewSchema.Card("card", "说明", "安全正文", List.of(action)),
                new ViewSchema.Progress("progress", "进度", "documents", "ratio", "label"),
                new ViewSchema.Timeline("timeline", "时间线", "documents", "time", "content"),
                new ViewSchema.Code("code", "代码", value, Optional.of(new ViewBinding("documents", "language"))),
                new ViewSchema.Artifact("artifact", "产物", value, new ViewBinding("documents", "mediaType")));
        ViewSchema schema = new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "all-passive-nodes",
                "全部安全节点",
                List.of(new ViewDataSource("documents", "documents/list", Map.of(), List.of(), 20)),
                nodes);

        assertEquals(6, schema.nodes().size());
        assertEquals("list", schema.nodes().getFirst().id());
        assertEquals("artifact", schema.nodes().getLast().id());
    }

    @Test
    void viewAction具有不可变值语义() {
        ViewAction base = baseAction();
        ViewAction equal = baseAction();

        assertEquals(base, base);
        assertEquals(base, equal);
        assertEquals(base.hashCode(), equal.hashCode());
        assertTrue(base.toString().contains("execution/start"));
        assertNotEquals(base, null);
        assertNotEquals(base, "action");
    }

    @Test
    void viewAction区分全部安全字段() {
        ViewAction base = baseAction();
        ViewCommandBinding binding = base.commandBindings().getFirst();

        assertNotEquals(
                base,
                actionLike(
                        "运行",
                        base.command(),
                        base.arguments(),
                        base.rowArguments(),
                        base.expectedRevision(),
                        false,
                        binding));
        assertNotEquals(
                base,
                actionLike(
                        base.label(),
                        "other",
                        base.arguments(),
                        base.rowArguments(),
                        base.expectedRevision(),
                        false,
                        binding));
        assertNotEquals(
                base,
                actionLike(
                        base.label(),
                        base.command(),
                        Map.of("mode", "fast"),
                        base.rowArguments(),
                        base.expectedRevision(),
                        false,
                        binding));
    }

    @Test
    void viewAction区分行参数revision危险标志和命令绑定() {
        ViewAction base = baseAction();
        ViewCommandBinding binding = base.commandBindings().getFirst();

        assertNotEquals(
                base,
                actionLike(
                        base.label(),
                        base.command(),
                        base.arguments(),
                        Map.of(),
                        base.expectedRevision(),
                        false,
                        binding));
        assertNotEquals(
                base,
                actionLike(
                        base.label(),
                        base.command(),
                        base.arguments(),
                        base.rowArguments(),
                        new ExpectedRevisionBinding.None(),
                        false,
                        binding));
        assertNotEquals(
                base,
                actionLike(
                        base.label(),
                        base.command(),
                        base.arguments(),
                        base.rowArguments(),
                        base.expectedRevision(),
                        true,
                        binding));
        assertNotEquals(
                base,
                new ViewAction(
                        base.label(),
                        base.command(),
                        base.arguments(),
                        base.rowArguments(),
                        base.expectedRevision(),
                        false));
    }

    @Test
    void viewAction限制命令绑定数量并拒绝空绑定() {
        ViewCommandBinding[] tooMany = IntStream.rangeClosed(0, ViewAction.MAX_COMMAND_BINDINGS)
                .mapToObj(index -> new ViewCommandBinding("arg" + index, new ViewBinding("source", "field" + index)))
                .toArray(ViewCommandBinding[]::new);

        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewAction("x", "x", Map.of(), Map.of(), new ExpectedRevisionBinding.None(), false, tooMany));
        assertThrows(
                NullPointerException.class,
                () -> new ViewAction(
                        "x", "x", Map.of(), Map.of(), new ExpectedRevisionBinding.None(), false, (ViewCommandBinding)
                                null));
    }

    @Test
    void optionBindingExtensionContext和Attachment策略保持声明式边界() {
        ViewOptionSource options = new ViewOptionSource("  providers  ", "  id  ", "  name  ", Optional.empty());
        ViewOptionFilter filter = new ViewOptionFilter("  toolName  ", "  selectedTool  ");
        ViewArgumentBinding argument = new ViewArgumentBinding("  providerId  ", "  providers  ", "  id  ");
        ExpectedRevisionBinding.RowField revision = new ExpectedRevisionBinding.RowField("  revision  ");
        ViewAttachmentPolicy policy = new ViewAttachmentPolicy(Set.of("TEXT/*", "application/pdf"), 1024);
        ExtensionContext context = new ExtensionContext(SpiFixtures.CLOCK, codec());

        assertEquals("providers", options.sourceId());
        assertEquals("toolName", filter.sourceField());
        assertEquals("selectedTool", filter.inputField());
        assertEquals("providerId", argument.argument());
        assertEquals("revision", revision.field());
        assertTrue(policy.accepts("text/plain; charset=utf-8"));
        assertFalse(policy.accepts("image/png"));
        assertEquals(SpiFixtures.payload(), context.payloads().encode("value"));
        assertThrows(IllegalArgumentException.class, () -> new ViewAttachmentPolicy(Set.of(), 1));
        assertThrows(IllegalArgumentException.class, () -> new ViewAttachmentPolicy(Set.of("text/plain"), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ViewAttachmentPolicy(Set.of("text/plain"), ViewAttachmentPolicy.PLATFORM_MAXIMUM_BYTES + 1));
        assertThrows(IllegalArgumentException.class, () -> policy.accepts("invalid"));
    }

    @Test
    void scalarViewFields接受匹配类型的选项上传策略和初值() {
        ViewOptionSource dynamic = new ViewOptionSource("options", "id", "name", Optional.empty());
        ViewAttachmentPolicy policy = new ViewAttachmentPolicy(Set.of("text/*"), 1024);
        ViewField staticChoice = field(
                ViewFieldType.CHOICE,
                Optional.of("one"),
                ViewFieldValidation.required(true),
                List.of(new ViewOption("one", "一")),
                Optional.empty());
        ViewField dynamicChoice = field(
                ViewFieldType.CHOICE,
                Optional.empty(),
                ViewFieldValidation.required(false),
                List.of(),
                Optional.of(dynamic));
        ViewField attachment = field(
                ViewFieldType.ATTACHMENT,
                Optional.empty(),
                ViewFieldValidation.attachment(true, policy),
                List.of(),
                Optional.empty());
        ViewField bool = field(
                ViewFieldType.BOOLEAN,
                Optional.of("false"),
                ViewFieldValidation.required(false),
                List.of(),
                Optional.empty());
        ViewField number =
                field(ViewFieldType.NUMBER, Optional.of("1.5"), boundedNumber(), List.of(), Optional.empty());

        assertEquals(new ViewBinding("editor", "value"), staticChoice.binding());
        assertTrue(dynamicChoice.optionSource().isPresent());
        assertEquals(policy, attachment.validation().attachment().orElseThrow());
        assertEquals("false", bool.initialValue().orElseThrow());
        assertEquals("1.5", number.initialValue().orElseThrow());
    }

    @Test
    void scalarViewFields拒绝错误选项和上传策略() {
        ViewOptionSource dynamic = new ViewOptionSource("options", "id", "name", Optional.empty());
        ViewAttachmentPolicy policy = new ViewAttachmentPolicy(Set.of("text/*"), 1024);

        assertInvalidField(
                ViewFieldType.CHOICE,
                Optional.empty(),
                ViewFieldValidation.required(false),
                List.of(),
                Optional.empty());
        assertInvalidField(
                ViewFieldType.TEXT,
                Optional.empty(),
                ViewFieldValidation.required(false),
                List.of(new ViewOption("one", "一")),
                Optional.empty());
        assertInvalidField(
                ViewFieldType.TEXT,
                Optional.empty(),
                ViewFieldValidation.required(false),
                List.of(),
                Optional.of(dynamic));
        assertInvalidField(
                ViewFieldType.ATTACHMENT,
                Optional.empty(),
                ViewFieldValidation.required(true),
                List.of(),
                Optional.empty());
        assertInvalidField(
                ViewFieldType.TEXT,
                Optional.empty(),
                ViewFieldValidation.attachment(true, policy),
                List.of(),
                Optional.empty());
    }

    @Test
    void scalarViewFields拒绝错误初值() {
        ViewAttachmentPolicy policy = new ViewAttachmentPolicy(Set.of("text/*"), 1024);

        assertInvalidField(
                ViewFieldType.ATTACHMENT,
                Optional.of("digest"),
                ViewFieldValidation.attachment(true, policy),
                List.of(),
                Optional.empty());
        assertInvalidField(
                ViewFieldType.BOOLEAN,
                Optional.of("yes"),
                ViewFieldValidation.required(false),
                List.of(),
                Optional.empty());
        assertThrows(
                NumberFormatException.class,
                () -> field(
                        ViewFieldType.NUMBER,
                        Optional.of("not-number"),
                        ViewFieldValidation.required(false),
                        List.of(),
                        Optional.empty()));
    }

    private static ViewAction baseAction() {
        return new ViewAction(
                "启动",
                "execution/start",
                Map.of("mode", "safe"),
                Map.of("definitionId", "id"),
                new ExpectedRevisionBinding.RowField("revision"),
                false,
                new ViewCommandBinding("profileId", new ViewBinding("profile", "id")));
    }

    private static ViewAction actionLike(
            String label,
            String command,
            Map<String, String> arguments,
            Map<String, String> rowArguments,
            ExpectedRevisionBinding revision,
            boolean dangerous,
            ViewCommandBinding binding) {
        return new ViewAction(label, command, arguments, rowArguments, revision, dangerous, binding);
    }

    private static ViewAction simpleAction(String label, String command, boolean dangerous) {
        return new ViewAction(label, command, Map.of(), Map.of(), new ExpectedRevisionBinding.None(), dangerous);
    }

    private static ViewField field(
            ViewFieldType type,
            Optional<String> initial,
            ViewFieldValidation validation,
            List<ViewOption> options,
            Optional<ViewOptionSource> optionSource) {
        return new ViewField(
                "value",
                "值",
                type,
                new ViewBinding("editor", "value"),
                initial,
                validation,
                options,
                optionSource,
                Optional.empty());
    }

    private static ViewFieldValidation boundedNumber() {
        return new ViewFieldValidation(
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.of(BigDecimal.ZERO),
                Optional.of(BigDecimal.TEN),
                Optional.empty());
    }

    private static void assertInvalidField(
            ViewFieldType type,
            Optional<String> initial,
            ViewFieldValidation validation,
            List<ViewOption> options,
            Optional<ViewOptionSource> optionSource) {
        assertThrows(RuntimeException.class, () -> field(type, initial, validation, options, optionSource));
    }

    private static ExtensionPayloadCodec codec() {
        return new ExtensionPayloadCodec() {
            @Override
            public CanonicalPayload encode(Object value) {
                return SpiFixtures.payload();
            }

            @Override
            public <T> T decode(CanonicalPayload payload, Class<T> type) {
                return null;
            }
        };
    }
}
