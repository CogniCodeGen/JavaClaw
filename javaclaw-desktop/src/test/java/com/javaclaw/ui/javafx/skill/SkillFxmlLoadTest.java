package com.javaclaw.ui.javafx.skill;

import com.javaclaw.api.interaction.ChoiceRequest;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.application.skill.SkillManagementApplicationService;
import com.javaclaw.application.skill.SkillManagementApplicationService.BundleCommand;
import com.javaclaw.application.skill.SkillManagementApplicationService.BundleItem;
import com.javaclaw.application.skill.SkillManagementApplicationService.ImportInspection;
import com.javaclaw.application.skill.SkillManagementApplicationService.ImportKind;
import com.javaclaw.application.skill.SkillManagementApplicationService.ImportResult;
import com.javaclaw.application.skill.SkillManagementApplicationService.OperationResult;
import com.javaclaw.application.skill.SkillManagementApplicationService.ProposalItem;
import com.javaclaw.application.skill.SkillManagementApplicationService.ReviewResult;
import com.javaclaw.application.skill.SkillManagementApplicationService.ScriptDocument;
import com.javaclaw.application.skill.SkillManagementApplicationService.ScriptMutation;
import com.javaclaw.application.skill.SkillManagementApplicationService.ScriptReport;
import com.javaclaw.application.skill.SkillManagementApplicationService.SkillDetail;
import com.javaclaw.application.skill.SkillManagementApplicationService.SkillSummary;
import com.javaclaw.application.skill.SkillManagementApplicationService.Snapshot;
import com.javaclaw.application.skill.SkillManagementApplicationService.UpdateSkillCommand;
import com.javaclaw.platform.desktop.ExternalDirectoryOpener;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fxml.ViewHandle;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.application.Platform;
import javafx.scene.control.ListView;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "javaclaw.fx.tests", matches = "true",
        disabledReason = "需要可用的 JavaFX 显示服务")
class SkillFxmlLoadTest {

    private static final long TIMEOUT_SECONDS = 5;
    private AnnotationConfigApplicationContext context;
    private SkillCenterView view;
    private FakeService service;

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
    void loadsCompleteWindowAndControllerTree() throws Exception {
        prepareContext();
        view = callFx(() -> context.getBean(SkillCenterViewFactory.class).create(null));
        runFx(view::show);

        awaitFx(() -> skillList().getItems().size() == 1);
        assertEquals(920.0, callFx(() -> view.root().getScene().getWidth()));
        assertEquals(650.0, callFx(() -> view.root().getScene().getHeight()));
        assertEquals("技能中心", callFx(() ->
                ((Stage) view.root().getScene().getWindow()).getTitle()));
        assertNotNull(callFx(() -> view.root().lookup("#emptyPanel")));
        assertNotNull(callFx(() -> view.root().lookup(".skill-editor")));
        assertNotNull(callFx(() -> view.root().lookup(".skill-proposals")));
        assertNotNull(callFx(() -> view.root().lookup(".skill-bundles")));

        runFx(() -> skillList().getSelectionModel().selectFirst());
        awaitFx(() -> view.root().lookup(".skill-editor").isVisible());
    }

    @Test
    void loadsReusableCellsCardsAndScriptDialog() throws Exception {
        prepareContext();
        callFx(() -> {
            SkillSummary skill = service.snapshot().skills().getFirst();
            SkillListCell skillCell = new SkillListCellFactory().create();
            skillCell.updateItem(skill, false);
            assertNotNull(skillCell.getGraphic());

            SkillBundleCell bundleCell = new SkillBundleCellFactory().create();
            bundleCell.updateItem(service.bundles().getFirst(), false);
            assertNotNull(bundleCell.getGraphic());

            SkillProposalCard card = new SkillProposalCardFactory().create(
                    service.proposals().getFirst(), ignored -> {}, ignored -> {});
            assertEquals(1, card.getChildren().size());

            try (ViewHandle<VBox> dialog = context.getBean(SpringFxmlLoader.class).load(
                    SkillFxmlLoadTest.class.getResource(
                            "/fxml/skill/skill-script-name-dialog.fxml"))) {
                assertNotNull(dialog.controller(SkillScriptNameDialogController.class));
            }
            return null;
        });
    }

    private void prepareContext() {
        context = new AnnotationConfigApplicationContext();
        service = new FakeService();
        context.registerBean(SkillManagementApplicationService.class, () -> service);
        context.registerBean(UserInteractionPort.class, AllowInteraction::new);
        context.registerBean(DialogService.class,
                () -> new DialogService(context.getBean(UserInteractionPort.class)));
        context.registerBean(ManagedTaskExecutor.class, () -> new ManagedTaskExecutor(),
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean("workspaceTaskScope", TaskScope.class,
                () -> context.getBean(ManagedTaskExecutor.class)
                        .openScope("skill-fxml-test", 16),
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
                        context.getBean(SpringFxmlLoader.class),
                        context.getBean(FxDispatcher.class),
                        new com.javaclaw.app.UIHelper(context.getBean(FxDispatcher.class))));
        context.registerBean(SkillCenterViewFactory.class,
                () -> new SkillCenterViewFactory(
                        context.getBean(SpringFxmlLoader.class), context.getBean(FxDispatcher.class)));
        context.refresh();
    }

    @SuppressWarnings("unchecked")
    private ListView<SkillSummary> skillList() {
        return (ListView<SkillSummary>) view.root().lookup("#skillList");
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
        callFx(() -> { action.run(); return null; });
    }

    private static <T> T callFx(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { result.set(action.call()); }
            catch (Throwable thrown) { failure.set(thrown); }
            finally { completed.countDown(); }
        });
        assertTrue(completed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
        return result.get();
    }

    private static final class AllowInteraction implements UserInteractionPort {
        @Override public boolean confirm(ConfirmRequest request) { return true; }
        @Override public String choose(ChoiceRequest request) { return null; }
        @Override public void notify(ToastRequest request) { }
    }

    private static final class FakeService implements SkillManagementApplicationService {
        private final Path root = Path.of("skills").toAbsolutePath();
        private final SkillDetail detail = new SkillDetail(
                "skill-1", "测试技能", "描述", "测试", List.of("java"), "正文", true,
                "1.0.0", "用户", false, false, null, List.of("0.9.0"),
                List.of("script.jsh"), "skill-1/\n  ├─ SKILL.md\n  ├─ scripts/",
                root.resolve("skill-1"));

        @Override public Snapshot snapshot() {
            return new Snapshot(List.of(new SkillSummary(
                    detail.id(), detail.name(), detail.description(), detail.version(),
                    detail.source(), detail.tags(), detail.agentCreated(), detail.enabled())), 1, root);
        }
        @Override public int pendingProposalCount() { return 1; }
        @Override public SkillDetail detail(String skillId) { return detail; }
        @Override public OperationResult create() { return result("已创建"); }
        @Override public OperationResult update(UpdateSkillCommand command) { return result("已保存"); }
        @Override public OperationResult delete(String skillId) {
            return new OperationResult(new Snapshot(List.of(), 1, root), null, "已删除");
        }
        @Override public OperationResult rollback(String skillId, String version) { return result("已回滚"); }
        @Override public ScriptDocument readScript(String skillId, String fileName) {
            return new ScriptDocument(fileName, "1 + 1");
        }
        @Override public ScriptMutation createScript(String skillId, String fileName) {
            return new ScriptMutation(detail, new ScriptDocument(fileName, ""), "已创建");
        }
        @Override public void saveScript(String skillId, String fileName, String content) { }
        @Override public ScriptMutation deleteScript(String skillId, String fileName) {
            return new ScriptMutation(detail, null, "已删除");
        }
        @Override public ScriptReport checkScript(String code) {
            return new ScriptReport(true, false, 0, "通过", "", List.of());
        }
        @Override public ScriptReport runScript(String skillId, String code, String arguments) {
            return new ScriptReport(true, false, 60, "", "2", List.of());
        }
        @Override public List<ProposalItem> proposals() {
            return List.of(new ProposalItem(
                    "p1", "edit", "测试技能", "优化", "新正文", false, 1));
        }
        @Override public ReviewResult approveProposal(String proposalId) {
            return new ReviewResult(List.of(), snapshot(), "已采纳");
        }
        @Override public ReviewResult rejectProposal(String proposalId) {
            return new ReviewResult(List.of(), snapshot(), "已拒绝");
        }
        @Override public AutoCloseable subscribeToProposalChanges(Runnable listener) { return () -> { }; }
        @Override public List<BundleItem> bundles() {
            return List.of(new BundleItem("测试包", "", List.of("测试技能"), "", true));
        }
        @Override public List<BundleItem> saveBundle(BundleCommand command) { return bundles(); }
        @Override public List<BundleItem> deleteBundle(String name) { return List.of(); }
        @Override public ImportInspection inspectImport(Path source, ImportKind kind) {
            return new ImportInspection(List.of());
        }
        @Override public ImportResult importSkill(Path source, ImportKind kind) {
            return new ImportResult(snapshot(), "已导入", source);
        }
        @Override public Path skillsDirectory() { return root; }
        @Override public Path skillDirectory(String skillId) { return root.resolve(skillId); }

        private OperationResult result(String message) {
            return new OperationResult(snapshot(), detail, message);
        }
    }
}
