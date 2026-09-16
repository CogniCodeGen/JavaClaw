package com.javaclaw.desktop.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;

import com.javaclaw.api.WorkspaceId;
import com.javaclaw.builtin.contracts.CodingSystemContracts;
import com.javaclaw.client.CommandOptions;
import com.javaclaw.desktop.component.FormSection;
import com.javaclaw.desktop.component.PlatformComponentFactory;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionSize;
import com.javaclaw.desktop.component.PlatformComponentFactory.ActionStyle;

/** 系统程序登记表单；只经 SDK 读写配置，异步结果必须匹配当前 Workspace 世代。 */
final class SystemCommandSettingsSection {
    private final CodingSettingsGateway gateway;
    private final PlatformComponentFactory components = new PlatformComponentFactory();
    private final FormSection section = new FormSection("系统程序", "登记程序后，在权限方案中授权对应程序和工具。Shell 使用系统环境，额外程序按登记的绝对路径调用。");
    private final ComboBox<CodingSystemContracts.Registration> selected = new ComboBox<>();
    private final TextField id = new TextField();
    private final TextField path = new TextField();
    private final TextField encoding = new TextField("UTF-8");
    private final TextArea reads = new TextArea();
    private final TextArea catalog = new TextArea();
    private final Label status = new Label();
    private final Button fresh;
    private final Button save;
    private final Button remove;
    private final Button refresh;
    private Optional<WorkspaceId> workspace = Optional.empty();
    private Optional<CodingSystemContracts.Registry> registry = Optional.empty();
    private long epoch;
    private boolean dirty;
    private boolean pending;
    private boolean rendering;

    SystemCommandSettingsSection(CodingSettingsGateway gateway) {
        this.gateway = gateway;
        fresh = button("登记新程序", ActionStyle.SOFT, () -> select(null));
        save = button("保存程序", ActionStyle.PRIMARY, this::save);
        remove = button("移除登记", ActionStyle.DANGER, this::remove);
        refresh = button("刷新程序目录", ActionStyle.GHOST, this::reload);
        build();
        updateButtons();
    }

    FormSection section() {
        return section;
    }

    boolean dirty() {
        return dirty;
    }

    boolean pending() {
        return pending;
    }

    void bind(Optional<WorkspaceId> next) {
        if (workspace.equals(next)) {
            return;
        }
        epoch++;
        workspace = next;
        registry = Optional.empty();
        pending = false;
        dirty = false;
        selected.getItems().clear();
        select(null);
        catalog.clear();
        reload();
    }

    void discard() {
        select(selected.getValue());
    }

    void dispose() {
        epoch++;
    }

    private void build() {
        selected.setId("codingSystemSelected");
        selected.setCellFactory(ignored -> components.textCell(CodingSystemContracts.Registration::id));
        selected.setButtonCell(components.textCell(CodingSystemContracts.Registration::id));
        selected.valueProperty().addListener((observable, before, after) -> {
            if (!rendering) {
                select(after);
            }
        });
        id.setId("codingSystemId");
        path.setId("codingSystemPath");
        encoding.setId("codingSystemEncoding");
        reads.setId("codingSystemReads");
        save.setId("codingSystemSave");
        remove.setId("codingSystemRemove");
        status.setId("codingSystemStatus");
        reads.setPrefRowCount(2);
        catalog.setPrefRowCount(5);
        catalog.setEditable(false);
        status.setWrapText(true);
        List.of(id, path, encoding)
                .forEach(field -> field.textProperty().addListener((observable, before, after) -> changed()));
        reads.textProperty().addListener((observable, before, after) -> changed());
        section.addField("已登记程序", selected);
        section.addField("程序 ID", id);
        section.addField("绝对路径", path);
        section.addField("输出编码", encoding);
        section.addOptionalField("依赖读取目录", reads, "每行一个绝对目录；仍需权限方案明确允许读取。");
        section.addFullWidth(new HBox(8, fresh, save, remove, refresh));
        section.addField("程序可用性", catalog);
        section.addFullWidth(status);
    }

    private void select(CodingSystemContracts.Registration entry) {
        rendering = true;
        try {
            selected.setValue(entry);
            id.setText(entry == null ? "" : entry.id());
            path.setText(entry == null ? "" : entry.path());
            encoding.setText(entry == null ? "UTF-8" : entry.outputEncoding());
            reads.setText(entry == null ? "" : String.join("\n", entry.readRoots()));
            dirty = false;
        } finally {
            rendering = false;
        }
        updateButtons();
    }

    private void changed() {
        if (!rendering) {
            dirty = true;
            status.setText("程序配置有未保存修改");
            updateButtons();
        }
    }

    private void reload() {
        if (workspace.isEmpty() || pending || dirty) {
            updateButtons();
            return;
        }
        pending = true;
        long requestEpoch = ++epoch;
        updateButtons();
        gateway.systemCommands(workspace.orElseThrow()).whenComplete((value, failure) -> {
            if (requestEpoch != epoch) {
                return;
            }
            pending = false;
            if (failure == null) {
                render(value.registry());
                catalog.setText(value.catalog().executables().stream()
                        .map(entry -> entry.id() + " · " + (entry.available() ? "可用" : "不可用") + " · " + entry.path()
                                + entry.unavailableReason()
                                        .map(reason -> " · " + reason)
                                        .orElse(""))
                        .collect(Collectors.joining("\n")));
                status.setText("");
            } else {
                status.setText(SettingsFailures.message(failure));
            }
            updateButtons();
        });
    }

    private void render(CodingSystemContracts.Registry value) {
        registry = Optional.of(value);
        rendering = true;
        selected.getItems().setAll(value.registrations());
        rendering = false;
        select(null);
    }

    private void save() {
        if (pending || workspace.isEmpty() || registry.isEmpty()) {
            return;
        }
        try {
            var entry = new CodingSystemContracts.Registration(
                    id.getText().strip(),
                    path.getText().strip(),
                    reads.getText()
                            .lines()
                            .map(String::strip)
                            .filter(value -> !value.isEmpty())
                            .toList(),
                    encoding.getText().strip());
            var entries = new ArrayList<>(registry.orElseThrow().registrations());
            entries.remove(selected.getValue());
            entries.add(entry);
            submit(new CodingSystemContracts.RegistryUpdate(entries));
        } catch (IllegalArgumentException failure) {
            status.setText(SettingsFailures.message(failure));
        }
    }

    private void remove() {
        if (pending || dirty || registry.isEmpty() || selected.getValue() == null) {
            return;
        }
        var entries = new ArrayList<>(registry.orElseThrow().registrations());
        entries.remove(selected.getValue());
        submit(new CodingSystemContracts.RegistryUpdate(entries));
    }

    private void submit(CodingSystemContracts.RegistryUpdate update) {
        pending = true;
        long requestEpoch = epoch;
        updateButtons();
        gateway.saveSystemCommands(
                        workspace.orElseThrow(),
                        update,
                        CommandOptions.create(registry.orElseThrow().revision()))
                .whenComplete((value, failure) -> {
                    if (requestEpoch != epoch) {
                        return;
                    }
                    pending = false;
                    if (failure == null) {
                        render(value);
                        status.setText("程序配置已保存，后续新任务使用新配置");
                        reload();
                    } else {
                        status.setText(SettingsFailures.message(failure));
                    }
                    updateButtons();
                });
    }

    private void updateButtons() {
        boolean unavailable = pending || workspace.isEmpty() || registry.isEmpty();
        fresh.setDisable(unavailable || dirty);
        save.setDisable(unavailable || !dirty);
        remove.setDisable(unavailable || dirty || selected.getValue() == null);
        refresh.setDisable(pending || workspace.isEmpty() || dirty);
        selected.setDisable(unavailable || dirty);
        List.of(id, path, encoding, reads).forEach(field -> field.setDisable(unavailable));
    }

    private Button button(String text, ActionStyle style, Runnable action) {
        Button result = components.action(text, style, ActionSize.NORMAL);
        result.setOnAction(event -> action.run());
        return result;
    }
}
