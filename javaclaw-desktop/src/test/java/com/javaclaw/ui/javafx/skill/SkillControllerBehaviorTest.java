package com.javaclaw.ui.javafx.skill;

import com.javaclaw.api.interaction.ChoiceRequest;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.application.skill.SkillManagementApplicationService;
import com.javaclaw.application.skill.SkillManagementApplicationService.BundleItem;
import com.javaclaw.application.skill.SkillManagementApplicationService.ImportInspection;
import com.javaclaw.application.skill.SkillManagementApplicationService.ImportKind;
import com.javaclaw.application.skill.SkillManagementApplicationService.OperationResult;
import com.javaclaw.application.skill.SkillManagementApplicationService.ProposalItem;
import com.javaclaw.application.skill.SkillManagementApplicationService.ReviewResult;
import com.javaclaw.application.skill.SkillManagementApplicationService.ScriptReport;
import com.javaclaw.application.skill.SkillManagementApplicationService.SkillDetail;
import com.javaclaw.application.skill.SkillManagementApplicationService.SkillSummary;
import com.javaclaw.application.skill.SkillManagementApplicationService.Snapshot;
import com.javaclaw.application.skill.SkillManagementApplicationService.Usage;
import com.javaclaw.platform.desktop.ExternalDirectoryOpener;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.ui.javafx.control.WindowToastController;
import javafx.application.Platform;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class SkillControllerBehaviorTest {

    private static final long TIMEOUT_SECONDS = 5;
    private AnnotationConfigApplicationContext context;
    private SkillCenterView view;
    private FakeSkillManagementService service;
    private ControllableInteraction interaction;

    @BeforeAll
    static void startToolkit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(() -> {
                Platform.setImplicitExit(false);
                started.countDown();
            });
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(started::countDown);
        }
        assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (view != null) runFx(view::close);
        if (context != null) context.close();
    }

    @Test
    void centerCoordinatesNavigationImportEventsAndFailures() throws Exception {
        SkillCenterController center = openView();
        SkillCenterViewModel model = field(center, "viewModel", SkillCenterViewModel.class);
        AtomicInteger closeRequests = new AtomicInteger();
        runFx(() -> center.configure(closeRequests::incrementAndGet));

        int detailsBefore = service.detailCalls;
        runFx(() -> invoke(center, "selectSkill", new Class<?>[] {String.class}, "skill-1"));
        awaitFx(() -> service.detailCalls > detailsBefore
                && model.panelProperty().get() == SkillCenterViewModel.Panel.EDITOR);

        int createsBefore = service.createCalls;
        runFx(() -> invoke(center, "createRequested"));
        awaitFx(() -> service.createCalls > createsBefore
                && toast(center).contains("已创建") && !busy(center));

        service.createResult = new OperationResult(service.snapshotValue(), null, "已清空");
        int emptyCreateBefore = service.createCalls;
        runFx(() -> invoke(center, "createRequested"));
        awaitFx(() -> service.createCalls > emptyCreateBefore
                && model.panelProperty().get() == SkillCenterViewModel.Panel.EMPTY);

        service.createFailure = new IllegalStateException();
        int failedCreateBefore = service.createCalls;
        runFx(() -> invoke(center, "createRequested"));
        awaitFx(() -> service.createCalls > failedCreateBefore
                && toast(center).contains("操作失败"));
        service.createFailure = null;
        service.createResult = service.result(service.detail, "已创建");

        int proposalLoadsBefore = service.proposalLoads;
        runFx(() -> invoke(center, "proposalsRequested"));
        awaitFx(() -> service.proposalLoads > proposalLoadsBefore
                && model.panelProperty().get() == SkillCenterViewModel.Panel.PROPOSALS);
        int bundleLoadsBefore = service.bundleLoads;
        runFx(() -> invoke(center, "bundlesRequested"));
        awaitFx(() -> service.bundleLoads > bundleLoadsBefore
                && model.panelProperty().get() == SkillCenterViewModel.Panel.BUNDLES);

        runFx(() -> {
            invoke(center, "chooseImportSource", new Class<?>[] {String.class}, (Object) null);
            invoke(center, "chooseImportSource", new Class<?>[] {String.class}, " ");
            invoke(center, "chooseImportSource", new Class<?>[] {String.class}, "unsupported");
        });

        Path source = Path.of("skill-source").toAbsolutePath();
        service.inspection = new ImportInspection(List.of());
        int importsBefore = service.importCalls;
        runFx(() -> invoke(center, "inspectImport",
                new Class<?>[] {Path.class, ImportKind.class}, source, ImportKind.DIRECTORY));
        awaitFx(() -> service.importCalls > importsBefore && toast(center).contains("已导入"));

        service.inspection = new ImportInspection(List.of("scripts/run.jsh"));
        interaction.confirmAllowed = false;
        int deniedImports = service.importCalls;
        int inspectionsBefore = service.inspectCalls;
        runFx(() -> invoke(center, "inspectImport",
                new Class<?>[] {Path.class, ImportKind.class}, source, ImportKind.ZIP));
        awaitFx(() -> service.inspectCalls > inspectionsBefore && !busy(center));
        assertEquals(deniedImports, service.importCalls);

        interaction.confirmAllowed = true;
        service.importInstalledDirectory = null;
        int nullDirectoryImports = service.importCalls;
        runFx(() -> invoke(center, "importSkill",
                new Class<?>[] {Path.class, ImportKind.class, ImportInspection.class},
                source, ImportKind.ZIP, service.inspection));
        awaitFx(() -> service.importCalls > nullDirectoryImports && !busy(center));

        service.inspectFailure = new IllegalStateException("inspect failed");
        int failedInspectionBefore = service.inspectCalls;
        runFx(() -> invoke(center, "inspectImport",
                new Class<?>[] {Path.class, ImportKind.class}, source, ImportKind.DIRECTORY));
        awaitFx(() -> service.inspectCalls > failedInspectionBefore
                && toast(center).contains("inspect failed"));
        service.inspectFailure = null;

        service.importFailure = new IllegalStateException("import failed");
        int failedImportBefore = service.importCalls;
        runFx(() -> invoke(center, "importSkill",
                new Class<?>[] {Path.class, ImportKind.class, ImportInspection.class},
                source, ImportKind.DIRECTORY, new ImportInspection(List.of())));
        awaitFx(() -> service.importCalls > failedImportBefore
                && toast(center).contains("import failed"));
        service.importFailure = null;

        runFx(() -> {
            model.snapshotProperty().set(null);
            invoke(center, "openSkillsDirectoryRequested");
            invoke(center, "applySnapshot", new Class<?>[] {Snapshot.class},
                    new Snapshot(List.of(), 0, service.root));
            assertEquals("待审提案", field(center, "proposalsButton",
                    javafx.scene.control.Button.class).getText());
            invoke(center, "editorChanged", new Class<?>[] {OperationResult.class},
                    service.result(service.detail, "编辑完成"));
            invoke(center, "proposalsChanged", new Class<?>[] {ReviewResult.class},
                    new ReviewResult(List.of(), service.snapshotValue(), "审阅完成"));
        });
        assertEquals("待审提案 (2)", callFx(() -> field(center, "proposalsButton",
                javafx.scene.control.Button.class).getText()));

        int eventSnapshots = service.snapshotCalls;
        int eventProposalLoads = service.proposalLoads;
        service.emitProposalChange();
        awaitFx(() -> service.snapshotCalls > eventSnapshots
                && service.proposalLoads > eventProposalLoads);

        runFx(() -> invoke(center, "closeRequested"));
        assertEquals(1, closeRequests.get());
        service.subscriptionCloseThrows = true;
        runFx(center::close);
        assertTrue(service.subscriptionClosed);
        int afterCloseSnapshots = service.snapshotCalls;
        runFx(() -> {
            invoke(center, "requestSnapshot");
            service.emitProposalChange();
            center.close();
        });
        assertEquals(afterCloseSnapshots, service.snapshotCalls);
    }

    @Test
    void editorAndScriptCoordinateFormsConfirmationsReportsAndMutations() throws Exception {
        SkillCenterController center = openView();
        SkillEditorController editor = field(center, "editorController", SkillEditorController.class);
        SkillScriptController scripts = field(editor, "scriptController", SkillScriptController.class);
        AtomicInteger editorChanges = new AtomicInteger();
        AtomicInteger scriptChanges = new AtomicInteger();
        runFx(() -> {
            editor.configure(ignored -> editorChanges.incrementAndGet());
            scripts.configure(ignored -> scriptChanges.incrementAndGet());
            invoke(editor, "saveRequested");
            invoke(editor, "rollbackRequested");
            invoke(editor, "deleteRequested");
            invoke(editor, "openDirectoryRequested");
            invoke(scripts, "createRequested");
            invoke(scripts, "runRequested");
            scripts.apply(service.detailWithScripts(List.of()));
            invoke(scripts, "saveRequested");
            invoke(scripts, "deleteRequested");
        });
        assertTrue(callFx(() -> status(scripts).getText().contains("请先选择脚本")));

        SkillDetail noUsage = service.detailWithUsage(Usage.empty(), false, false);
        SkillDetail unknownRate = service.detailWithUsage(new Usage(2, 3, 0, -1), true, true);
        SkillDetail knownRate = service.detailWithUsage(new Usage(4, 5, 2, 0.625), false, false);
        runFx(() -> {
            editor.apply(noUsage);
            assertEquals("尚无使用统计", field(editor, "usageLabel", Label.class).getText());
            editor.apply(unknownRate);
            assertTrue(field(editor, "usageLabel", Label.class).getText().endsWith("—"));
            editor.apply(knownRate);
            assertTrue(field(editor, "usageLabel", Label.class).getText().endsWith("63%"));
            textField(editor, "tagsField").setText("java, ， tools,  ");
            invoke(editor, "saveRequested");
        });
        awaitFx(() -> service.updateCalls == 1 && editorChanges.get() == 1);
        assertEquals(List.of("java", "tools"), service.lastUpdate.tags());

        service.updateResult = new OperationResult(service.snapshotValue(), null, "仅保存");
        runFx(() -> invoke(editor, "saveRequested"));
        awaitFx(() -> service.updateCalls == 2 && editorChanges.get() == 2);

        service.updateFailure = new IllegalStateException();
        runFx(() -> invoke(editor, "saveRequested"));
        awaitFx(() -> service.updateCalls == 3
                && status(editor).getText().contains("操作失败"));
        service.updateFailure = null;

        runFx(() -> {
            editor.apply(service.detail);
            field(editor, "historyCombo", ComboBox.class).getSelectionModel().clearSelection();
            invoke(editor, "rollbackRequested");
        });
        assertTrue(callFx(() -> status(editor).getText().contains("请先选择历史版本")));

        interaction.confirmAllowed = false;
        runFx(() -> {
            field(editor, "historyCombo", ComboBox.class).setValue("0.9.0");
            invoke(editor, "rollbackRequested");
        });
        awaitFx(() -> !busy(editor));
        assertEquals(0, service.rollbackCalls);

        interaction.confirmAllowed = true;
        runFx(() -> invoke(editor, "rollbackRequested"));
        awaitFx(() -> service.rollbackCalls == 1);

        interaction.confirmAllowed = false;
        runFx(() -> invoke(editor, "deleteRequested"));
        awaitFx(() -> !busy(editor));
        assertEquals(0, service.deleteCalls);
        interaction.confirmAllowed = true;
        runFx(() -> invoke(editor, "deleteRequested"));
        awaitFx(() -> service.deleteCalls == 1 && editorChanges.get() >= 4);

        runFx(() -> {
            editor.apply(service.detail);
            invoke(editor, "applyScriptDetail", new Class<?>[] {SkillDetail.class},
                    service.otherDetail());
            invoke(editor, "applyScriptDetail", new Class<?>[] {SkillDetail.class},
                    service.detailWithScripts(List.of("one.jsh", "two.java")));
        });

        runFx(() -> scripts.apply(service.detailWithScripts(List.of("one.jsh", "two.java"))));
        awaitFx(() -> service.readCalls > 0
                && textArea(scripts, "scriptEditor").getText().contains("loaded"));

        runFx(() -> {
            textArea(scripts, "scriptEditor").setText("1 + 2");
            invoke(scripts, "saveRequested");
        });
        awaitFx(() -> service.saveScriptCalls == 1
                && status(scripts).getText().contains("已保存"));

        service.saveScriptFailure = new IllegalStateException("save failed");
        runFx(() -> invoke(scripts, "saveRequested"));
        awaitFx(() -> service.saveScriptCalls == 2
                && status(scripts).getText().contains("save failed"));
        service.saveScriptFailure = null;

        interaction.confirmAllowed = false;
        runFx(() -> invoke(scripts, "deleteRequested"));
        awaitFx(() -> !field(scripts, "saveButton", javafx.scene.control.Button.class).isDisabled());
        assertEquals(0, service.deleteScriptCalls);
        interaction.confirmAllowed = true;
        runFx(() -> invoke(scripts, "deleteRequested"));
        awaitFx(() -> service.deleteScriptCalls == 1 && scriptChanges.get() == 1);

        runFx(() -> invoke(scripts, "create", new Class<?>[] {String.class}, "new.jsh"));
        awaitFx(() -> service.createScriptCalls == 1 && scriptChanges.get() == 2);

        service.checkReport = new ScriptReport(true, false, 0, "", "", List.of());
        runFx(() -> invoke(scripts, "checkRequested"));
        awaitFx(() -> service.checkCalls == 1
                && textArea(scripts, "scriptOutputArea").getText().contains("无输出"));

        service.checkReport = new ScriptReport(false, true, 9, "line\n", "42",
                List.of("problem"));
        runFx(() -> invoke(scripts, "checkRequested"));
        awaitFx(() -> service.checkCalls == 2
                && textArea(scripts, "scriptOutputArea").getText().contains("执行超时")
                && textArea(scripts, "scriptOutputArea").getText().contains("[诊断]"));

        service.runReport = new ScriptReport(false, false, 0, "bad", "", List.of());
        runFx(() -> invoke(scripts, "runRequested"));
        awaitFx(() -> service.runCalls == 1
                && status(scripts).getText().contains("运行有错误"));

        service.runFailure = new IllegalStateException();
        runFx(() -> invoke(scripts, "runRequested"));
        awaitFx(() -> service.runCalls == 2
                && status(scripts).getText().contains("操作失败"));
        service.runFailure = null;

        service.readFailure = new IllegalStateException("read failed");
        int readsBeforeFailure = service.readCalls;
        runFx(() -> invoke(scripts, "load", new Class<?>[] {String.class}, "two.java"));
        awaitFx(() -> service.readCalls > readsBeforeFailure
                && status(scripts).getText().contains("read failed"));
        service.readFailure = null;

        runFx(() -> {
            scripts.close();
            scripts.close();
            scripts.apply(service.detail);
            editor.close();
            editor.close();
        });
    }

    @Test
    void bundlesProposalsAndReusableCellsCoverAllDisplayStates() throws Exception {
        SkillCenterController center = openView();
        SkillBundlesController bundles = field(center, "bundlesController", SkillBundlesController.class);
        SkillProposalsController proposals = field(center, "proposalsController",
                SkillProposalsController.class);
        AtomicInteger reviews = new AtomicInteger();
        runFx(() -> proposals.configure(ignored -> reviews.incrementAndGet()));

        int bundleLoadsBefore = service.bundleLoads;
        runFx(bundles::refresh);
        awaitFx(() -> service.bundleLoads > bundleLoadsBefore
                && bundleList(bundles).getItems().size() == 2);
        runFx(() -> {
            bundleList(bundles).getSelectionModel().selectFirst();
            invoke(bundles, "newRequested");
            invoke(bundles, "deleteRequested");
            textField(bundles, "nameField").setText("新包");
            textField(bundles, "skillsField").setText(" alpha,， beta, ");
            field(bundles, "enabledCheck", CheckBox.class).setSelected(false);
            invoke(bundles, "saveRequested");
        });
        awaitFx(() -> service.saveBundleCalls == 1
                && status(bundles).getText().contains("已保存"));
        assertEquals(List.of("alpha", "beta"), service.lastBundle.skills());

        service.bundleFailure = new IllegalStateException();
        runFx(() -> invoke(bundles, "saveRequested"));
        awaitFx(() -> service.saveBundleCalls == 2
                && status(bundles).getText().contains("操作失败"));
        service.bundleFailure = null;

        runFx(() -> {
            setField(bundles, "selectedName", "新包");
            invoke(bundles, "deleteRequested");
        });
        awaitFx(() -> service.deleteBundleCalls == 1
                && status(bundles).getText().contains("已删除"));

        int proposalLoadsBefore = service.proposalLoads;
        runFx(proposals::refresh);
        awaitFx(() -> service.proposalLoads > proposalLoadsBefore
                && proposalList(proposals).getChildren().size() == 2);
        runFx(() -> invoke(proposals, "render", new Class<?>[] {List.class}, List.of()));
        assertTrue(callFx(() -> field(proposals, "emptyLabel", Label.class).isVisible()));

        runFx(() -> invoke(proposals, "review",
                new Class<?>[] {String.class, boolean.class}, "p1", true));
        awaitFx(() -> service.approveCalls == 1 && reviews.get() == 1);
        runFx(() -> invoke(proposals, "review",
                new Class<?>[] {String.class, boolean.class}, "p2", false));
        awaitFx(() -> service.rejectCalls == 1 && reviews.get() == 2);

        service.proposalFailure = new IllegalStateException();
        runFx(proposals::refresh);
        awaitFx(() -> status(proposals).getText().contains("操作失败"));
        runFx(() -> invoke(proposals, "review",
                new Class<?>[] {String.class, boolean.class}, "p1", true));
        awaitFx(() -> service.approveCalls == 2);
        service.proposalFailure = null;

        runFx(() -> {
            SkillProposalCard card = new SkillProposalCard();
            invoke(card, "approveRequested");
            invoke(card, "rejectRequested");
            AtomicReference<String> approved = new AtomicReference<>();
            AtomicReference<String> rejected = new AtomicReference<>();
            card.apply(new ProposalItem("create", "create", "新技能", "", "preview",
                    true, System.currentTimeMillis()), approved::set, rejected::set);
            invoke(card, "approveRequested");
            invoke(card, "rejectRequested");
            assertEquals("create", approved.get());
            assertEquals("create", rejected.get());

            SkillBundleCell bundleCell = new SkillBundleCellFactory().create();
            bundleCell.updateItem(new BundleItem("空包", "", List.of(), "", false), false);
            bundleCell.updateSelected(true);
            bundleCell.updateSelected(false);
            bundleCell.updateItem(null, true);
            SkillListCell skillCell = new SkillListCellFactory().create();
            skillCell.updateItem(new SkillSummary("disabled", "禁用", "", "1", "用户",
                    List.of(), true, false), false);
            skillCell.updateSelected(true);
            skillCell.updateSelected(false);
            skillCell.updateItem(null, true);

            bundles.close();
            bundles.refresh();
            bundles.close();
            proposals.close();
            proposals.refresh();
            proposals.close();
        });
    }

    private SkillCenterController openView() throws Exception {
        prepareContext();
        view = callFx(() -> context.getBean(SkillCenterViewFactory.class).create(null));
        SkillCenterController center = view.controller();
        runFx(() -> {
            view.root().applyCss();
            view.root().layout();
            center.prepare();
        });
        awaitFx(() -> service.snapshotCalls > 0 && skillList(center).getItems().size() == 1);
        return center;
    }

    private void prepareContext() {
        context = new AnnotationConfigApplicationContext();
        service = new FakeSkillManagementService();
        interaction = new ControllableInteraction();
        context.registerBean(SkillManagementApplicationService.class, () -> service);
        context.registerBean(UserInteractionPort.class, () -> interaction);
        context.registerBean(DialogService.class,
                () -> new DialogService(context.getBean(UserInteractionPort.class)));
        context.registerBean(ManagedTaskExecutor.class, () -> new ManagedTaskExecutor(),
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean("workspaceTaskScope", TaskScope.class,
                () -> context.getBean(ManagedTaskExecutor.class)
                        .openScope("skill-controller-test", 16),
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean(FxDispatcher.class, FxDispatcher::new);
        context.registerBean(ExternalDirectoryOpener.class,
                () -> new ExternalDirectoryOpener(context.getBean(ManagedTaskExecutor.class)));
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getBeanFactory()));
        context.registerBean(SkillListCellFactory.class, SkillListCellFactory::new);
        context.registerBean(SkillBundleCellFactory.class, SkillBundleCellFactory::new);
        context.registerBean(SkillProposalCardFactory.class, SkillProposalCardFactory::new);
        context.registerBean(SkillScriptNameDialogFactory.class,
                () -> new SkillScriptNameDialogFactory(
                        context.getBean(SpringFxmlLoader.class), context.getBean(FxDispatcher.class),
                        new com.javaclaw.app.UIHelper(context.getBean(FxDispatcher.class))));
        context.registerBean(SkillCenterViewFactory.class,
                () -> new SkillCenterViewFactory(
                        context.getBean(SpringFxmlLoader.class), context.getBean(FxDispatcher.class)));
        context.refresh();
    }

    private static String toast(SkillCenterController center) {
        WindowToastController toast = field(center, "toastController", WindowToastController.class);
        return field(toast, "messageLabel", Label.class).getText();
    }

    private static Label status(Object controller) {
        return field(controller, "statusLabel", Label.class);
    }

    private static boolean busy(Object controller) {
        return field(controller, "loadingOverlay", StackPane.class).isVisible();
    }

    @SuppressWarnings("unchecked")
    private static ListView<SkillSummary> skillList(SkillCenterController center) {
        return field(center, "skillList", ListView.class);
    }

    @SuppressWarnings("unchecked")
    private static ListView<BundleItem> bundleList(SkillBundlesController controller) {
        return field(controller, "bundleList", ListView.class);
    }

    private static VBox proposalList(SkillProposalsController controller) {
        return field(controller, "proposalList", VBox.class);
    }

    private static TextField textField(Object target, String name) {
        return field(target, name, TextField.class);
    }

    private static TextArea textArea(Object target, String name) {
        return field(target, name, TextArea.class);
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法读取测试字段：" + name, failure);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法设置测试字段：" + name, failure);
        }
    }

    private static Object invoke(Object target, String methodName) {
        return invoke(target, methodName, new Class<?>[0]);
    }

    private static Object invoke(
            Object target, String methodName, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("无法调用 Controller 方法：" + methodName, failure);
        }
    }

    private static void awaitFx(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (callFx(condition::getAsBoolean)) return;
            Thread.sleep(10);
        }
        assertTrue(callFx(condition::getAsBoolean), "等待 JavaFX 状态超时");
    }

    private static void runFx(Runnable action) throws Exception {
        callFx(() -> {
            action.run();
            return null;
        });
    }

    private static <T> T callFx(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(action.call());
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                completed.countDown();
            }
        });
        assertTrue(completed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }

    private static final class ControllableInteraction implements UserInteractionPort {
        private volatile boolean confirmAllowed = true;
        private volatile String choice;

        @Override public boolean confirm(ConfirmRequest request) { return confirmAllowed; }
        @Override public String choose(ChoiceRequest request) { return choice; }
        @Override public void notify(ToastRequest request) { }
    }

}
