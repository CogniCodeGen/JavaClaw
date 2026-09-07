package com.javaclaw.desktop.settings;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;

import com.javaclaw.api.AgentRole;
import com.javaclaw.api.AgentRoleFileExport;
import com.javaclaw.api.AgentRoleFileFormat;
import com.javaclaw.api.AgentRoleFilePreview;
import com.javaclaw.api.AgentRoleRef;
import com.javaclaw.api.ProviderEndpoint;
import com.javaclaw.api.ProviderLifecycle;
import com.javaclaw.api.ProviderModelPurpose;
import com.javaclaw.api.ProviderRef;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformDialogs;

/**
 * 角色 TOML 交换面板；只导入明确确认的预览，不监听文件变化，不自动猜测模型映射。
 *
 * <p>预览与目标 revision 始终成对保存；异步读取和写入绑定请求代次，失效响应不改变当前表单。
 */
final class AgentRoleFilePanel {
    private static final int MAXIMUM_BYTES = 1024 * 1024;
    private final CoreSettingsGateway gateway;
    private final Consumer<AgentRole> imported;
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final FormSection content = new FormSection("角色文件", "单个 UTF-8 TOML 文件最多 1 MiB。导入先显示差异，确认后保存；文件后续变化不会影响角色。");
    private final ComboBox<AgentRoleFileFormat> format = new ComboBox<>();
    private final ComboBox<ProviderRef> mapping = new ComboBox<>();
    private final TextArea previewText = new TextArea();
    private final Label status = new Label();
    private final Button commit = new Button("确认导入");
    private final Button export = new Button("导出角色");
    private final Button importFile = new Button("选择文件并预览");
    private Optional<AgentRole> selected = Optional.empty();
    private Optional<ImportPreview> preview = Optional.empty();
    private Runnable pendingChanged = () -> {};
    private boolean pending;
    private boolean importBlocked;
    private boolean disposed;
    private long epoch;

    AgentRoleFilePanel(CoreSettingsGateway gateway, Consumer<AgentRole> imported) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.imported = Objects.requireNonNull(imported, "imported");
        format.getItems().setAll(AgentRoleFileFormat.values());
        format.setValue(AgentRoleFileFormat.JAVACLAW_LOSSLESS);
        format.setCellFactory(ignored -> components.textCell(AgentRoleFilePanel::formatName));
        format.setButtonCell(components.textCell(AgentRoleFilePanel::formatName));
        mapping.setPromptText("选择文件模型对应的 Provider");
        mapping.setCellFactory(ignored -> components.textCell(value -> value.endpointId() + " · " + value.model()));
        mapping.setButtonCell(components.textCell(value -> value.endpointId() + " · " + value.model()));
        previewText.setEditable(false);
        previewText.setWrapText(true);
        previewText.setPrefRowCount(5);
        status.setWrapText(true);
        status.getStyleClass().add("sec-hint");
        content.addField("导入 / 导出模式", format);
        content.addFullWidth(new HBox(8, importFile, export));
        content.addField("待确认差异", previewText);
        content.addField("未解析模型映射", mapping);
        content.addFullWidth(new HBox(8, commit, status));
        commit.setDisable(true);
        importFile.setOnAction(event -> chooseImport());
        commit.setOnAction(event -> confirmImport());
        export.setOnAction(event -> exportFile());
        mapping.valueProperty().addListener((ignored, before, after) -> updateActions());
    }

    Node content() {
        return content;
    }

    boolean pending() {
        return pending;
    }

    void onPendingChanged(Runnable listener) {
        pendingChanged = Objects.requireNonNull(listener, "listener");
    }

    void blockImport(boolean blocked) {
        importBlocked = blocked;
        updateActions();
    }

    void bind(Optional<AgentRole> role, List<ProviderEndpoint> providers) {
        boolean changed = !selected.equals(role);
        selected = Objects.requireNonNull(role, "role");
        if (changed) {
            epoch++;
            preview = Optional.empty();
            previewText.clear();
            mapping.setValue(null);
            setPending(false);
        }
        mapping.getItems()
                .setAll(providers.stream()
                        .filter(endpoint -> endpoint.lifecycle() == ProviderLifecycle.ACTIVE)
                        .flatMap(endpoint -> endpoint.spec().models().stream()
                                .filter(model -> model.supports(ProviderModelPurpose.CHAT))
                                .map(model -> new ProviderRef(endpoint.id(), endpoint.revision(), model.modelId())))
                        .toList());
        updateActions();
    }

    void dispose() {
        disposed = true;
        epoch++;
        preview = Optional.empty();
        pendingChanged = () -> {};
        setPending(false);
    }

    private void chooseImport() {
        if (!canImport()) {
            return;
        }
        FileChooser chooser = chooser();
        File file = chooser.showOpenDialog(content.getScene().getWindow());
        if (file == null) {
            return;
        }
        String suggested = file.getName().replaceFirst("\\.agent\\.toml$|\\.toml$", "");
        PlatformDialogs.requiredText(
                        content, "导入 Agent", "确认目标角色标识", "相同标识会预览与现有角色的差异。内置角色不能覆盖。", "角色标识", suggested, "生成预览")
                .showAndWait()
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .ifPresent(id -> readImport(file.toPath(), id));
    }

    private void readImport(Path path, String id) {
        if (!canImport()) {
            return;
        }
        preview = Optional.empty();
        long request = begin("正在读取并验证文件…");
        AgentRoleFileFormat mode = format.getValue();
        CompletableFuture.supplyAsync(() -> readUtf8(path))
                .whenComplete((text, failure) -> Platform.runLater(() -> {
                    if (!current(request)) {
                        return;
                    }
                    if (failure != null) {
                        failed(request, failure);
                    } else {
                        requestPreview(request, id, text, mode);
                    }
                }));
    }

    void previewImport(String id, String text, AgentRoleFileFormat mode) {
        if (!canImport()) {
            return;
        }
        preview = Optional.empty();
        requestPreview(begin("正在验证角色文件…"), id, text, mode);
    }

    private void requestPreview(long request, String id, String text, AgentRoleFileFormat mode) {
        gateway.roles().whenComplete((roles, failure) -> {
            if (!current(request)) {
                return;
            }
            if (failure != null) {
                failed(request, failure);
                return;
            }
            long expectedRevision = roles.stream()
                    .filter(role -> role.id().equals(id))
                    .mapToLong(AgentRole::revision)
                    .findFirst()
                    .orElse(0L);
            gateway.previewRoleImport(id, text, mode)
                    .whenComplete((value, error) -> showPreview(request, expectedRevision, value, error));
        });
    }

    private void showPreview(long request, long expectedRevision, AgentRoleFilePreview value, Throwable failure) {
        if (!current(request)) {
            return;
        }
        if (failure != null) {
            failed(request, failure);
            return;
        }
        preview = Optional.of(new ImportPreview(value, expectedRevision));
        previewText.setText("角色：" + value.roleId() + "\n摘要：" + value.contentDigest()
                + "\n变化字段：" + String.join("、", value.changedFields()) + "\n名称："
                + value.spec().name()
                + "\n用途：" + value.spec().description() + "\n角色指令：\n"
                + value.spec().developerInstructions());
        status.setText(value.unresolvedModel()
                .map(model -> "模型 “" + model + "” 需要手动选择 Provider")
                .orElse("预览尚未保存，请检查差异后确认导入"));
        mapping.setValue(null);
        setPending(false);
    }

    private void confirmImport() {
        if (!canCommit()) {
            return;
        }
        ImportPreview checked = preview.orElseThrow();
        long request = epoch;
        Alert dialog = new Alert(
                Alert.AlertType.CONFIRMATION,
                "将保存预览中的角色 “" + checked.value().roleId() + "”。",
                ButtonType.CANCEL,
                ButtonType.OK);
        dialog.setTitle("确认导入 Agent");
        dialog.setHeaderText("确认已审阅角色差异与模型映射");
        PlatformDialogs.style(dialog, content);
        if (dialog.showAndWait().filter(ButtonType.OK::equals).isEmpty()
                || !current(request)
                || !preview.equals(Optional.of(checked))) {
            return;
        }
        commitPreview();
    }

    /** 提交已经由外层确认的预览；仍重新检查当前草稿与操作互斥条件。 */
    void commitPreview() {
        if (!canCommit()) {
            return;
        }
        ImportPreview checked = preview.orElseThrow();
        Optional<ProviderRef> modelMapping = Optional.ofNullable(mapping.getValue());
        long request = begin("正在导入角色…");
        gateway.commitRoleImport(
                        checked.value().previewId(), modelMapping, CommandOptions.create(checked.expectedRevision()))
                .whenComplete((role, failure) -> {
                    if (!current(request)) {
                        return;
                    }
                    if (failure != null) {
                        failed(request, failure);
                    } else {
                        preview = Optional.empty();
                        status.setText("已导入 Agent 版本 " + role.revision());
                        setPending(false);
                        imported.accept(role);
                    }
                });
    }

    private void exportFile() {
        if (!canImport() || selected.isEmpty()) {
            return;
        }
        AgentRole role = selected.orElseThrow();
        long request = begin("正在导出角色…");
        gateway.exportRole(new AgentRoleRef(role.id(), role.revision()), format.getValue())
                .whenComplete((result, failure) -> {
                    if (!current(request)) {
                        return;
                    }
                    if (failure != null) {
                        failed(request, failure);
                    } else {
                        saveExport(request, result);
                    }
                });
    }

    private void saveExport(long request, AgentRoleFileExport result) {
        FileChooser chooser = chooser();
        chooser.setInitialFileName(result.filename());
        File destination = chooser.showSaveDialog(content.getScene().getWindow());
        if (!current(request)) {
            return;
        }
        if (destination != null) {
            try {
                Files.writeString(destination.toPath(), result.content(), StandardCharsets.UTF_8);
                status.setText("已导出 · SHA-256 " + result.contentDigest());
            } catch (IOException failure) {
                failed(request, failure);
            }
        }
        setPending(false);
    }

    private void failed(long request, Throwable failure) {
        if (!current(request)) {
            return;
        }
        status.setText(SettingsFailures.message(failure));
        setPending(false);
    }

    private void updateActions() {
        importFile.setDisable(!canImport());
        export.setDisable(!canImport() || selected.isEmpty());
        commit.setDisable(!canCommit());
        format.setDisable(pending || disposed);
        mapping.setDisable(!canImport()
                || preview.flatMap(value -> value.value().unresolvedModel()).isEmpty());
    }

    private boolean canImport() {
        return !pending && !importBlocked && !disposed;
    }

    private boolean canCommit() {
        return canImport()
                && preview.isPresent()
                && (preview.orElseThrow().value().unresolvedModel().isEmpty() || mapping.getValue() != null);
    }

    private long begin(String message) {
        long request = ++epoch;
        status.setText(message);
        setPending(true);
        return request;
    }

    private boolean current(long request) {
        return !disposed && request == epoch;
    }

    private void setPending(boolean value) {
        boolean changed = pending != value;
        pending = value;
        updateActions();
        if (changed) {
            pendingChanged.run();
        }
    }

    private static FileChooser chooser() {
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Agent TOML", "*.toml"));
        return chooser;
    }

    private static String readUtf8(Path path) {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAXIMUM_BYTES + 1);
            if (bytes.length > MAXIMUM_BYTES) {
                throw new IllegalArgumentException("角色文件超过 1 MiB");
            }
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (IOException failure) {
            throw new IllegalArgumentException("无法读取 UTF-8 角色文件", failure);
        }
    }

    private static String formatName(AgentRoleFileFormat value) {
        return value == AgentRoleFileFormat.CODEX_PORTABLE ? "Codex 可移植" : "JavaClaw 完整";
    }

    /**
     * 待确认预览与其读取前的目标版本不可拆开替换，避免确认时自动覆盖更新的角色。
     *
     * @param value SDK 返回的非空预览
     * @param expectedRevision 读取预览前的目标版本；0 表示创建，正数表示更新
     */
    private record ImportPreview(AgentRoleFilePreview value, long expectedRevision) {}
}
