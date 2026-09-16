package com.javaclaw.desktop.view;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.AttachmentRef;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.extension.spi.ExpectedRevisionBinding;
import com.javaclaw.extension.spi.ViewAction;
import com.javaclaw.extension.spi.ViewAttachmentPolicy;
import com.javaclaw.extension.spi.ViewBinding;
import com.javaclaw.extension.spi.ViewDataSource;
import com.javaclaw.extension.spi.ViewField;
import com.javaclaw.extension.spi.ViewFieldType;
import com.javaclaw.extension.spi.ViewFieldValidation;
import com.javaclaw.extension.spi.ViewSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewRenderLayoutTest {
    @Test
    void 自定义布局只重组权威节点且刷新关闭均取消隐藏上传() {
        FxTestSupport.run(() -> {
            HiddenLayout layout = new HiddenLayout();
            Uploads interactions = new Uploads();
            ViewSchema schema = schema();
            ViewRenderSession session = new ViewRenderSession(schema, new ViewSchemaRenderer(), layout);
            session.apply(ViewData.empty(), interactions);
            assertSame(layout.node(), session.node());
            assertSame(schema, layout.schema);
            assertEquals(List.of("upload", "notice"), new ArrayList<>(layout.nodes.keySet()));
            assertThrows(UnsupportedOperationException.class, () -> layout.nodes.clear());
            assertTrue(((VBox) layout.node()).getChildren().isEmpty(), "隐藏节点仍由会话持有，不能依赖可见父子树清理");

            ViewAttachmentFieldControl first = attachment(layout.nodes.get("upload"));
            first.upload(Path.of("hidden-first.txt"));
            assertTrue(first.pending());
            session.apply(ViewData.empty(), interactions);
            assertFalse(first.pending());
            assertTrue(interactions.requests.getFirst().cancellation().isCancelled());

            ViewAttachmentFieldControl second = attachment(layout.nodes.get("upload"));
            second.upload(Path.of("hidden-second.txt"));
            session.close();
            session.close();
            assertFalse(second.pending());
            assertTrue(interactions.requests.getLast().cancellation().isCancelled());
            assertEquals(1, layout.closed);
            assertFalse(session.accepts(schema));
            assertThrows(IllegalStateException.class, () -> session.apply(ViewData.empty(), interactions));
        });
    }

    private static ViewAttachmentFieldControl attachment(Node node) {
        if (node instanceof ViewAttachmentFieldControl attachment) {
            return attachment;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                ViewAttachmentFieldControl found = attachment(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static ViewSchema schema() {
        ViewField attachment = new ViewField(
                "attachment",
                "附件",
                ViewFieldType.ATTACHMENT,
                new ViewBinding("editor", "attachment"),
                Optional.empty(),
                ViewFieldValidation.attachment(true, new ViewAttachmentPolicy(Set.of("text/*"), 1024)),
                List.of(),
                Optional.empty(),
                Optional.empty());
        return new ViewSchema(
                ViewSchema.CURRENT_VERSION,
                "layout",
                "布局测试",
                List.of(new ViewDataSource("editor", "read", Map.of(), List.of(), 1)),
                List.of(
                        new ViewSchema.Form(
                                "upload",
                                "导入",
                                List.of(attachment),
                                new ViewAction(
                                        "导入", "import", Map.of(), Map.of(), new ExpectedRevisionBinding.None(), false)),
                        new ViewSchema.Card("notice", "说明", "平台说明", List.of())));
    }

    private static final class HiddenLayout implements ViewRenderLayout {
        private final VBox root = new VBox();
        private Map<String, Node> nodes;
        private ViewSchema schema;
        private int closed;

        @Override
        public Node node() {
            return root;
        }

        @Override
        public void apply(ViewSchema definition, Map<String, Node> renderedNodes, ViewData data) {
            schema = definition;
            nodes = renderedNodes;
        }

        @Override
        public void close() {
            nodes = Map.of();
            closed++;
        }
    }

    private static final class Uploads implements ViewInteractionHandler {
        private final List<ViewAttachmentUploadRequest> requests = new ArrayList<>();

        @Override
        public void dirty(String formId, boolean dirty) {}

        @Override
        public void execute(ViewCommandInvocation invocation) {}

        @Override
        public void reload() {}

        @Override
        public void page(String sourceId, ViewPageDirection direction) {}

        @Override
        public void select(String sourceId, Optional<String> selectedKey) {}

        @Override
        public CompletionStage<AttachmentRef> upload(ViewAttachmentUploadRequest request) {
            requests.add(request);
            return new CompletableFuture<>();
        }
    }
}
