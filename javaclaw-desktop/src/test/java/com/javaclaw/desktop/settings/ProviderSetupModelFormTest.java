package com.javaclaw.desktop.settings;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import org.junit.jupiter.api.Test;

import com.javaclaw.api.ProviderImageSupport;
import com.javaclaw.api.ProviderModelDiscoveryCandidate;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderModelSpec;
import com.javaclaw.desktop.FxTestSupport;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformStylesheets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderSetupModelFormTest {
    @Test
    void 目录焦点筛选与刷新不发送草稿变化只有用户勾选才通知() {
        FxTestSupport.run(() -> {
            ProviderSetupModelForm form = form();
            AtomicInteger changes = new AtomicInteger();
            form.onChanged(changes::incrementAndGet);
            form.seed(List.of(
                    new ProviderModelSpec("saved", "已保存模型", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty())));
            form.candidates(List.of(candidate("candidate", "候选模型")));
            form.focusModel("candidate");
            text(form, "providerWizardSearch").setText("missing");
            filter(form).setValue("已选模型");
            form.candidates(List.of(candidate("another", "另一模型")));
            form.connectionChanged();
            text(form, "providerWizardSearch").clear();
            filter(form).setValue("全部模型");
            assertEquals(0, changes.get(), "浏览和目录回执不得清除宿主保存错误或制造脏草稿");
            choice(form, "已保存模型 · saved").fire();
            assertEquals(1, changes.get());
        });
    }

    @Test
    void 按ID搜索勾选后行焦点直接定位详情且筛选不会丢失保存选择() {
        FxTestSupport.run(() -> {
            ProviderSetupModelForm form = form();
            form.candidates(List.of(candidate("opaque-123", "易读名称"), candidate("another-id", "另一个名称")));
            text(form, "providerWizardSearch").setText("OPAQUE-123");
            choice(form, "易读名称 · opaque-123").fire();
            assertEquals("opaque-123", form.currentModel());
            assertEquals("opaque-123", list(form).getSelectionModel().getSelectedItem());
            assertNull(form.lookup("#providerWizardSelectedModel"));
            assertTrue(list(form).lookupAll(".combo-box").isEmpty());
            assertTrue(list(form).lookupAll(".text-field").isEmpty());
            text(form, "providerWizardSearch").setText("another-id");
            assertEquals("opaque-123", form.currentModel());
            choice(form, "另一个名称 · another-id").fire();
            assertEquals("another-id", form.currentModel());
            assertEquals(2, form.selectedModels().size());
            filter(form).setValue("已选模型");
            text(form, "providerWizardSearch").clear();
            assertEquals(List.of("opaque-123", "another-id"), list(form).getItems());
        });
    }

    @Test
    void 行焦点和保存勾选相互独立且未勾选属性只读() {
        FxTestSupport.run(() -> {
            ProviderSetupModelForm form = form();
            form.candidates(List.of(candidate("first", "第一模型"), candidate("second", "第二模型")));
            list(form).getSelectionModel().select("second");
            assertEquals("second", form.currentModel());
            assertTrue(form.selectedModels().isEmpty());
            assertTrue(purpose(form, "providerWizardModelPurpose").isDisabled());
            assertFalse(form.lookup("#providerWizardModelFields").isManaged());
            assertFalse(text(form, "providerWizardCurrentModelId").isEditable());
            choice(form, "第二模型 · second").fire();
            assertFalse(purpose(form, "providerWizardModelPurpose").isDisabled());
            assertTrue(form.lookup("#providerWizardModelFields").isManaged());
            assertFalse(form.lookup("#providerWizardModelSelectionHint").isManaged());
            choice(form, "第二模型 · second").fire();
            assertEquals("second", form.currentModel());
            assertTrue(form.selectedModels().isEmpty());
            assertTrue(purpose(form, "providerWizardModelPurpose").isDisabled());
        });
    }

    @Test
    void 详情切换保留各模型图片声明维度及尚未通过校验的输入() {
        FxTestSupport.run(() -> {
            ProviderSetupModelForm form = form();
            form.seed(List.of(
                    new ProviderModelSpec("chat", "对话模型", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty()),
                    new ProviderModelSpec(
                            "embedding", "向量模型", Set.of(ProviderModelPurpose.EMBEDDING), OptionalInt.of(1024))));
            form.focusModel("embedding");
            text(form, "providerWizardModelDimensions").setText("尚未填完");
            form.focusModel("chat");
            images(form).setValue(ProviderImageSupport.SUPPORTED);
            form.focusModel("embedding");
            assertEquals("尚未填完", text(form, "providerWizardModelDimensions").getText());
            assertThrows(IllegalArgumentException.class, form::selectedModels);
            text(form, "providerWizardModelDimensions").setText("1536");
            List<ProviderModelSpec> models = form.selectedModels();
            assertEquals(ProviderImageSupport.SUPPORTED, models.getFirst().imageSupport());
            assertEquals(OptionalInt.of(1536), models.getLast().embeddingDimensions());
            assertEquals(
                    Set.of(ProviderModelPurpose.EMBEDDING), models.getLast().purposes());
        });
    }

    @Test
    void 搜索和目录回执保持同一详情输入节点及光标选择() {
        FxTestSupport.run(() -> {
            ProviderSetupModelForm form = form();
            form.seed(List.of(new ProviderModelSpec(
                    "embedding", "向量模型", Set.of(ProviderModelPurpose.EMBEDDING), OptionalInt.of(1024))));
            TextField dimensions = text(form, "providerWizardModelDimensions");
            dimensions.setText("768");
            dimensions.selectRange(1, 3);
            text(form, "providerWizardSearch").setText("no-match");
            form.candidates(List.of(candidate("remote-model", "新候选")));
            assertSame(dimensions, text(form, "providerWizardModelDimensions"));
            assertEquals("768", dimensions.getText());
            assertEquals(new IndexRange(1, 3), dimensions.getSelection());
            assertEquals("embedding", form.currentModel());
        });
    }

    @Test
    void 手动添加入口展开表单且折叠时未确认输入继续阻止保存() {
        FxTestSupport.run(() -> {
            ProviderSetupModelForm form = form();
            TitledPane section = (TitledPane) form.lookup("#providerWizardManualSection");
            Button add = button(form, "providerWizardAddManual");
            assertFalse(section.isExpanded());
            assertFalse(section.isManaged());
            assertTrue(add.isDisabled());
            button(form, "providerWizardShowManual").fire();
            assertTrue(section.isExpanded());
            text(form, "providerWizardManualModel").setText("my-manual-model");
            assertFalse(add.isDisabled());
            section.setExpanded(false);
            assertFalse(section.isVisible());
            assertFalse(section.isManaged());
            assertTrue(section.getText().contains("待确认输入"));
            assertThrows(IllegalArgumentException.class, form::selectedModels);
            section.setExpanded(true);
            add.fire();
            assertEquals("my-manual-model", form.currentModel());
            assertFalse(section.isExpanded());
            assertTrue(add.isDisabled());
            assertFalse(section.getText().contains("待确认输入"));
            assertEquals(1, form.selectedModels().size());
        });
    }

    @Test
    void 紧凑高度手动编辑替换目录并在添加后恢复同一目录和焦点模型() {
        FxTestSupport.run(() -> {
            ProviderSetupModelForm form = form();
            form.seed(List.of(
                    new ProviderModelSpec("saved", "已选模型", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty())));
            Parent directory = (Parent) form.lookup("#providerWizardDirectory");
            TitledPane manual = (TitledPane) form.lookup("#providerWizardManualSection");
            form.resize(600, 320);
            form.layout();
            assertTrue(list(form).getHeight() >= 110);
            button(form, "providerWizardShowManual").fire();
            text(form, "providerWizardManualModel").setText("manual-model");
            form.layout();
            assertFalse(directory.isManaged());
            assertFalse(directory.isVisible());
            assertTrue(manual.isManaged());
            assertTrue(manual.getLayoutY() + manual.getHeight() <= form.getHeight() + 1);
            manual.setExpanded(false);
            assertTrue(directory.isManaged());
            assertEquals("manual-model", text(form, "providerWizardManualModel").getText());
            assertThrows(IllegalArgumentException.class, form::selectedModels);
            button(form, "providerWizardShowManual").fire();
            button(form, "providerWizardAddManual").fire();
            assertFalse(manual.isManaged());
            assertTrue(directory.isVisible());
            assertEquals("manual-model", form.currentModel());
            assertEquals(2, form.selectedModels().size());
        });
    }

    @Test
    void 紧凑高度手动与批量互斥且扩大视口或应用批量后恢复目录() {
        FxTestSupport.run(() -> {
            ProviderSetupModelForm form = form();
            form.seed(List.of(
                    new ProviderModelSpec("one", "one", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty()),
                    new ProviderModelSpec("two", "two", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty())));
            Parent directory = (Parent) form.lookup("#providerWizardDirectory");
            Parent batch = (Parent) form.lookup("#providerWizardBatchSection");
            TitledPane manual = (TitledPane) form.lookup("#providerWizardManualSection");
            form.resize(600, 320);
            button(form, "providerWizardShowManual").fire();
            text(form, "providerWizardManualModel").setText("unfinished");
            button(form, "providerWizardBatchToggle").fire();
            form.layout();
            assertFalse(manual.isManaged());
            assertTrue(batch.isManaged());
            assertFalse(directory.isManaged());
            assertTrue(batch.getLayoutY() + batch.getLayoutBounds().getHeight() <= form.getHeight() + 1);
            form.resize(600, 500);
            assertTrue(directory.isManaged());
            form.resize(600, 320);
            assertFalse(directory.isManaged());
            purpose(form, "providerWizardBatchPurpose").setValue(ProviderSetupPurposeChoice.EMBEDDING);
            button(form, "providerWizardApplyPurpose").fire();
            assertFalse(batch.isManaged());
            assertTrue(directory.isManaged());
            assertEquals("unfinished", text(form, "providerWizardManualModel").getText());
            assertThrows(IllegalArgumentException.class, form::selectedModels);
            text(form, "providerWizardManualModel").clear();
            assertTrue(
                    form.selectedModels().stream().allMatch(model -> model.supports(ProviderModelPurpose.EMBEDDING)));
        });
    }

    @Test
    void 未知用途显示待完善且批量用途仅在多选后按需显示() {
        FxTestSupport.run(() -> {
            ProviderSetupModelForm form = form();
            form.candidates(List.of(unknown("embedding-name"), unknown("chat-name")));
            choice(form, "embedding-name").fire();
            assertTrue(((Label) form.lookup("#providerWizardModelSelectionHint"))
                    .getText()
                    .contains("待完善"));
            assertFalse(button(form, "providerWizardBatchToggle").isVisible());
            assertThrows(IllegalArgumentException.class, form::selectedModels);
            choice(form, "chat-name").fire();
            assertTrue(button(form, "providerWizardBatchToggle").isVisible());
            assertFalse(form.lookup("#providerWizardBatchSection").isVisible());
            button(form, "providerWizardBatchToggle").fire();
            assertTrue(form.lookup("#providerWizardBatchSection").isVisible());
            purpose(form, "providerWizardBatchPurpose").setValue(ProviderSetupPurposeChoice.EMBEDDING);
            button(form, "providerWizardApplyPurpose").fire();
            assertTrue(form.selectedModels().stream()
                    .allMatch(model -> model.purposes().equals(Set.of(ProviderModelPurpose.EMBEDDING))));
            assertEquals(
                    ProviderSetupPurposeChoice.EMBEDDING,
                    purpose(form, "providerWizardModelPurpose").getValue());
        });
    }

    @Test
    void 连接变化舍弃未选旧目录但保留已选属性与手动草稿() {
        FxTestSupport.run(() -> {
            ProviderSetupModelForm form = form();
            form.candidates(List.of(candidate("keep", "已选模型"), candidate("old", "旧目录")));
            choice(form, "已选模型 · keep").fire();
            images(form).setValue(ProviderImageSupport.UNSUPPORTED);
            list(form).getSelectionModel().select("old");
            text(form, "providerWizardManualModel").setText("尚未确认");
            form.connectionChanged();
            assertEquals(List.of("keep"), list(form).getItems());
            assertEquals("keep", form.currentModel());
            assertEquals("尚未确认", text(form, "providerWizardManualModel").getText());
            assertEquals(ProviderImageSupport.UNSUPPORTED, images(form).getValue());
            text(form, "providerWizardManualModel").clear();
            assertEquals(
                    ProviderImageSupport.UNSUPPORTED,
                    form.selectedModels().getFirst().imageSupport());
        });
    }

    @Test
    void 数千候选仅创建可见单元格且筛选后已选模型仍存在() {
        FxTestSupport.run(() -> {
            ProviderSetupModelForm form = form();
            form.candidates(IntStream.range(0, 3000)
                    .mapToObj(index -> candidate("model-" + index, "模型 " + index))
                    .toList());
            form.applyCss();
            form.layout();
            assertEquals(3000, list(form).getItems().size());
            // 断言实际节点数，防止改回每个候选创建一个 CheckBox 的非虚拟化实现。
            assertTrue(list(form).lookupAll(".check-box").size() < 50);
            text(form, "providerWizardSearch").setText("model-2999");
            choice(form, "模型 2999 · model-2999").fire();
            text(form, "providerWizardSearch").clear();
            filter(form).setValue("已选模型");
            assertEquals(List.of("model-2999"), list(form).getItems());
            assertEquals("model-2999", form.selectedModels().getFirst().modelId());
        });
    }

    @Test
    void 宽窄布局切换仅重排目录详情而不重建输入字段() {
        FxTestSupport.run(() -> {
            ProviderSetupModelForm form = form();
            form.seed(List.of(new ProviderModelSpec(
                    "both",
                    "对话与向量",
                    Set.of(ProviderModelPurpose.CHAT, ProviderModelPurpose.EMBEDDING),
                    OptionalInt.empty())));
            BorderPane directory = (BorderPane) form.lookup("#providerWizardDirectory");
            Parent details = (Parent) form.lookup("#providerWizardModelDetails");
            TextField dimensions = text(form, "providerWizardModelDimensions");
            form.resize(900, 620);
            form.applyCss();
            form.layout();
            assertSame(details, directory.getRight());
            assertTrue(form.lookup("#providerWizardModelHeading").isManaged());
            assertTrue(
                    details.sceneToLocal(images(form).localToScene(images(form).getBoundsInLocal()))
                                    .getMaxX()
                            <= details.getLayoutBounds().getWidth() + 1,
                    "图片声明下拉框必须完整容纳于侧栏");
            assertTrue(details.sceneToLocal(dimensions.localToScene(dimensions.getBoundsInLocal()))
                            .getMaxX()
                    <= details.getLayoutBounds().getWidth() + 1);
            form.resize(480, 620);
            form.applyCss();
            form.layout();
            ScrollPane detailScroll = (ScrollPane) directory.getBottom();
            assertSame(details, detailScroll.getContent());
            assertEquals("providerWizardModelDetailsScroll", detailScroll.getId());
            assertFalse(form.lookup("#providerWizardModelHeading").isManaged());
            assertTrue(
                    details.sceneToLocal(images(form).localToScene(images(form).getBoundsInLocal()))
                                    .getMaxX()
                            <= details.getLayoutBounds().getWidth() + 1);
            assertSame(dimensions, text(form, "providerWizardModelDimensions"));
            assertSame(list(form), directory.getCenter());
        });
    }

    @Test
    void 窄窗详情独立滚动完整访问多用途字段且为目录保留最小高度() {
        FxTestSupport.run(() -> {
            ProviderSetupModelForm form = form();
            form.selectionSummary();
            form.seed(List.of(new ProviderModelSpec(
                    "both",
                    "多用途模型",
                    Set.of(ProviderModelPurpose.CHAT, ProviderModelPurpose.EMBEDDING),
                    OptionalInt.empty())));
            form.resize(600, 250);
            form.applyCss();
            form.layout();
            ScrollPane scroll = (ScrollPane) form.lookup("#providerWizardModelDetailsScroll");
            assertTrue(list(form).getHeight() >= 110);
            assertTrue(scroll.getHeight() <= 130);
            scroll.setVvalue(1);
            form.layout();
            var viewport = scroll.lookup(".viewport");
            var visible = viewport.localToScene(viewport.getLayoutBounds());
            TextField dimensions = text(form, "providerWizardModelDimensions");
            var bounds = dimensions.localToScene(dimensions.getLayoutBounds());
            assertTrue(bounds.getMinY() >= visible.getMinY() - 1, "字段不能落在详情视口上方裁切区");
            assertTrue(bounds.getMaxY() <= visible.getMaxY() + 1, "滚动后字段必须完整位于详情视口内");
            assertSame(dimensions, text(form, "providerWizardModelDimensions"));
        });
    }

    @Test
    void 选择摘要移至宿主标题后仍更新计数并控制同一批量面板() {
        FxTestSupport.run(() -> {
            ProviderSetupModelForm form = form();
            Parent summary = (Parent) form.lookup("#providerWizardSelectionSummary");
            assertSame(form, summary.getParent(), "兼容向导默认在表单内显示摘要");
            assertSame(summary, form.selectionSummary());
            assertNull(form.lookup("#providerWizardSelectionSummary"));
            HBox header = new HBox(summary);
            form.seed(List.of(
                    new ProviderModelSpec("one", "one", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty()),
                    new ProviderModelSpec("two", "two", Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty())));
            assertEquals("已选 2 个模型", ((Label) summary.lookup("#providerWizardSelectedCount")).getText());
            form.resize(600, 320);
            button(summary, "providerWizardBatchToggle").fire();
            assertTrue(form.lookup("#providerWizardBatchSection").isManaged());
            assertFalse(form.lookup("#providerWizardDirectory").isManaged());
            button(summary, "providerWizardBatchToggle").fire();
            assertTrue(form.lookup("#providerWizardDirectory").isManaged());
            assertSame(summary, form.selectionSummary());
            assertSame(header, summary.getParent(), "重复读取摘要不能从宿主标题移除节点");
        });
    }

    private static ProviderSetupModelForm form() {
        var form = new ProviderSetupModelForm(new PlatformComponentFactory(), () -> {});
        new Scene(form, 600, 700);
        PlatformStylesheets.applyTo(form);
        form.resize(600, 700);
        form.applyCss();
        form.layout();
        return form;
    }

    private static CheckBox choice(Parent root, String label) {
        root.applyCss();
        root.layout();
        return root.lookupAll(".check-box").stream()
                .filter(CheckBox.class::isInstance)
                .map(CheckBox.class::cast)
                .filter(value -> label.equals(value.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static TextField text(Parent root, String id) {
        return (TextField) root.lookup("#" + id);
    }

    private static Button button(Parent root, String id) {
        return (Button) root.lookup("#" + id);
    }

    @SuppressWarnings("unchecked")
    private static ListView<String> list(Parent root) {
        return (ListView<String>) root.lookup("#providerWizardModelsList");
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<String> filter(Parent root) {
        return (ComboBox<String>) root.lookup("#providerWizardModelFilter");
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<ProviderSetupPurposeChoice> purpose(Parent root, String id) {
        return (ComboBox<ProviderSetupPurposeChoice>) root.lookup("#" + id);
    }

    private static ProviderImageSupportField images(Parent root) {
        return (ProviderImageSupportField) root.lookup(".provider-image-support");
    }

    private static ProviderModelDiscoveryCandidate candidate(String id, String name) {
        return new ProviderModelDiscoveryCandidate(id, name, Set.of(ProviderModelPurpose.CHAT), OptionalInt.empty());
    }

    private static ProviderModelDiscoveryCandidate unknown(String id) {
        return new ProviderModelDiscoveryCandidate(id, id, Set.of(), OptionalInt.empty());
    }
}
