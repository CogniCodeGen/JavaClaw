package com.javaclaw.ui.javafx.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javaclaw.api.interaction.ConfirmRequest;
import com.javaclaw.api.interaction.ToastRequest;
import com.javaclaw.api.interaction.UserInteractionPort;
import com.javaclaw.application.agent.AgentManagementApplicationService;
import com.javaclaw.framework.api.CapabilityForm;
import com.javaclaw.framework.api.CapabilityId;
import com.javaclaw.platform.dialog.DialogService;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fx.FxDispatcher;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
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
class AgentSettingsFxmlLoadTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final CapabilityId TEST_CAPABILITY = new CapabilityId("test.capability");
    private AnnotationConfigApplicationContext context;
    private AgentSettingsPanel panel;
    private AgentSettingsController controller;

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
        if (panel != null) runFx(panel::close);
        if (context != null) context.close();
    }

    @Test
    void supportsLoadValidationSaveOptimizeCreateDeleteAndClose() throws Exception {
        FakeService service = new FakeService();
        AtomicInteger changes = new AtomicInteger();
        createContext(service);
        runFx(() -> {
            panel = context.getBean(AgentSettingsPanelFactory.class).create(changes::incrementAndGet);
            new Scene(panel.root(), 840, 640);
            panel.root().applyCss();
            controller = panel.controller();
            panel.activate();
        });

        awaitFx(() -> box("builtInRows").getChildren().size() == 1
                && box("customRows").getChildren().size() == 1);
        runFx(() -> box("customRows").getChildren().getFirst()
                .getOnMouseClicked().handle(null));
        assertEquals("自定义", callFx(() -> text("nameField").getText()));
        awaitFx(() -> box("capabilityFormsBox").getChildren().size() == 1);
        assertTrue(callFx(() -> capabilityToggle().isSelected()));
        assertEquals("initial", callFx(() -> capabilityTextField().getText()));

        runFx(() -> {
            text("nameField").clear();
            button("saveButton").fire();
        });
        awaitFx(() -> label("statusLabel").getText().contains("名称不能为空"));

        runFx(() -> {
            text("nameField").setText("Java 专家");
            text("toolNameField").setText("java_expert");
            capabilityTextField().setText("updated");
            button("saveButton").fire();
        });
        awaitFx(() -> "Java 专家".equals(service.require("custom-1").name())
                && "updated".equals(service.require("custom-1").capabilityBindings()
                        .get(TEST_CAPABILITY).path("endpoint").asText())
                && changes.get() == 1);

        runFx(() -> button("optimizePromptButton").fire());
        awaitFx(() -> "优化结果".equals(area("systemPromptArea").getText()));

        runFx(() -> button("addButton").fire());
        awaitFx(() -> box("customRows").getChildren().size() == 2);
        assertEquals("新智能体", callFx(() -> text("nameField").getText()));

        runFx(() -> button("deleteButton").fire());
        awaitFx(() -> box("customRows").getChildren().size() == 1);
        assertEquals(2, changes.get());

        runFx(panel::close);
        panel = null;
        assertTrue(controller.isClosed());
    }

    private void createContext(FakeService service) {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(AgentManagementApplicationService.class, () -> service);
        context.registerBean(ObjectMapper.class, AgentSettingsFxmlLoadTest::productionObjectMapper);
        context.registerBean(UserInteractionPort.class, AllowInteraction::new);
        context.registerBean(ManagedTaskExecutor.class, () -> new ManagedTaskExecutor(),
                definition -> definition.setDestroyMethodName("close"));
        context.registerBean(FxDispatcher.class, FxDispatcher::new);
        context.registerBean(DialogService.class,
                () -> new DialogService(context.getBean(UserInteractionPort.class)));
        context.registerBean(SpringFxmlLoader.class,
                () -> new SpringFxmlLoader(context.getAutowireCapableBeanFactory()));
        context.registerBean(AgentRowFactory.class,
                () -> new AgentRowFactory(context.getBean(SpringFxmlLoader.class)));
        context.registerBean(AgentSettingsPanelFactory.class,
                () -> new AgentSettingsPanelFactory(context.getBean(SpringFxmlLoader.class)));
        context.refresh();
    }

    private VBox box(String id) { return (VBox) panel.root().lookup("#" + id); }
    private Button button(String id) { return (Button) panel.root().lookup("#" + id); }
    private Label label(String id) { return (Label) panel.root().lookup("#" + id); }
    private TextField text(String id) { return (TextField) panel.root().lookup("#" + id); }
    private TextArea area(String id) { return (TextArea) panel.root().lookup("#" + id); }

    private CheckBox capabilityToggle() {
        VBox card = (VBox) box("capabilityFormsBox").getChildren().getFirst();
        return (CheckBox) card.getChildren().get(2);
    }

    private TextField capabilityTextField() {
        VBox card = (VBox) box("capabilityFormsBox").getChildren().getFirst();
        VBox fields = (VBox) card.getChildren().get(3);
        VBox row = (VBox) fields.getChildren().getFirst();
        return (TextField) row.getChildren().get(1);
    }

    private static ObjectMapper productionObjectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
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
        @Override public void notify(ToastRequest request) {}
    }

    private static final class FakeService implements AgentManagementApplicationService {
        private final List<Agent> agents = new ArrayList<>(List.of(
                new Agent("builtin", "内置", "coding_expert", "", "", 1, true, true),
                new Agent("custom-1", "自定义", "custom_one", "描述", "草稿", 2, true,
                        false, java.util.Map.of(TEST_CAPABILITY, capabilityValue("initial")))));
        private int sequence = 1;

        @Override
        public synchronized Catalog catalog() {
            return new Catalog(agents, List.of(capabilityForm()));
        }

        @Override
        public synchronized ChangeResult create() {
            Agent created = new Agent("custom-" + (++sequence), "新智能体",
                    "custom_" + sequence, "", "", 1, true, false);
            agents.add(created);
            return new ChangeResult(created, catalog());
        }

        @Override
        public synchronized Catalog save(SaveAgentCommand command) {
            if (command.name() == null || command.name().isBlank()) {
                throw new com.javaclaw.application.error.ValidationException("名称不能为空");
            }
            agents.removeIf(agent -> agent.id().equals(command.id()));
            agents.add(new Agent(command.id(), command.name(), command.toolName(),
                    command.description(), command.systemPrompt(), command.maxIters(),
                    command.enabled(), false, command.capabilityBindings()));
            return catalog();
        }

        @Override
        public synchronized Catalog delete(String agentId) {
            agents.removeIf(agent -> agent.id().equals(agentId));
            return catalog();
        }

        @Override public String optimize(OptimizePromptCommand command) { return "优化结果"; }

        synchronized Agent require(String id) { return catalog().require(id); }

        private static CapabilityForm capabilityForm() {
            ObjectNode schema = JsonNodeFactory.instance.objectNode();
            schema.put("type", "object");
            ObjectNode endpoint = schema.putObject("properties").putObject("endpoint");
            endpoint.put("type", "string");
            endpoint.put("title", "服务地址");
            return new CapabilityForm(TEST_CAPABILITY, "test.extension", "测试能力",
                    "验证扩展能力表单可加载、编辑并保存", schema,
                    JsonNodeFactory.instance.objectNode());
        }

        private static ObjectNode capabilityValue(String endpoint) {
            return JsonNodeFactory.instance.objectNode().put("endpoint", endpoint);
        }
    }
}
