package com.javaclaw.ui.javafx.memory;

import com.javaclaw.application.memory.MemoryApplicationService;
import com.javaclaw.application.memory.MemoryApplicationService.OperationResult;
import com.javaclaw.application.memory.MemoryApplicationService.PersonaDraft;
import com.javaclaw.application.memory.MemoryApplicationService.Snapshot;
import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.platform.fx.UiAsyncAction;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.springframework.beans.factory.annotation.Qualifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 结构化人格表单和导出流程。 */
public final class MemoryPersonaController
        implements MemoryHostedSection, AutoCloseable {
    private static final String DIRECT = "简洁直接";
    private static final String PATIENT = "耐心细致";
    private static final String LIVELY = "活泼鼓励";

    @FXML private TextArea identity;
    @FXML private Button directTone;
    @FXML private Button patientTone;
    @FXML private Button livelyTone;
    @FXML private VBox preferences;
    @FXML private VBox taboos;
    @FXML private TextField preferenceInput;
    @FXML private TextField tabooInput;
    @FXML private Label preview;
    @FXML private Button saveButton;

    private final MemoryApplicationService useCases;
    private final MemoryComponentFactory components;
    private final UiAsyncAction<OperationResult> saveAction;
    private final UiAsyncAction<Void> exportAction;
    private final List<String> preferenceValues = new ArrayList<>();
    private final List<String> tabooValues = new ArrayList<>();
    private final List<MemoryChildView<?>> preferenceViews = new ArrayList<>();
    private final List<MemoryChildView<?>> tabooViews = new ArrayList<>();
    private MemorySectionHost host;
    private String tone = DIRECT;

    public MemoryPersonaController(
            MemoryApplicationService useCases,
            MemoryComponentFactory components,
            @Qualifier("workspaceTaskScope") TaskScope tasks,
            FxDispatcher fx) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.components = Objects.requireNonNull(components, "components");
        saveAction = new UiAsyncAction<>(tasks, fx);
        exportAction = new UiAsyncAction<>(tasks, fx);
    }

    @FXML
    private void initialize() {
        identity.textProperty().addListener((ignored, previous, value) -> updatePreview());
        saveButton.disableProperty().bind(saveAction.busyProperty());
    }

    @Override public void configure(MemorySectionHost host) { this.host = host; }

    @Override
    public void apply(Snapshot snapshot, String query) {
        PersonaDraft persona = snapshot.persona();
        identity.setText(persona.identity());
        tone = persona.tone();
        preferenceValues.clear();
        preferenceValues.addAll(persona.preferences());
        tabooValues.clear();
        tabooValues.addAll(persona.taboos());
        renderEntries();
        refreshToneButtons();
        updatePreview();
    }

    @FXML private void selectDirectTone() { selectTone(DIRECT); }
    @FXML private void selectPatientTone() { selectTone(PATIENT); }
    @FXML private void selectLivelyTone() { selectTone(LIVELY); }
    @FXML private void addPreference() { add(preferenceInput, preferenceValues); }
    @FXML private void addTaboo() { add(tabooInput, tabooValues); }

    @FXML
    private void save() {
        PersonaDraft draft = draft();
        saveAction.execute(TaskSpec.io("memory-save-persona"),
                context -> useCases.savePersona(draft), host::apply,
                failure -> host.showMessage("保存人格失败：" + failure.getMessage()));
    }

    @FXML
    private void export() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出人格为 Markdown");
        chooser.setInitialFileName("persona.md");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Markdown", "*.md"));
        java.io.File selected = chooser.showSaveDialog(host.window());
        if (selected == null) return;
        Path target = selected.toPath();
        PersonaDraft draft = draft();
        exportAction.execute(TaskSpec.io("memory-export-persona"), context -> {
            useCases.exportPersona(target, draft);
            return null;
        }, ignored -> host.showMessage("已导出到 " + target.getFileName()),
                failure -> host.showMessage("导出人格失败：" + failure.getMessage()));
    }

    private void add(TextField input, List<String> target) {
        String value = input.getText() == null ? "" : input.getText().strip();
        if (value.isBlank()) return;
        target.add(value);
        input.clear();
        renderEntries();
        updatePreview();
    }

    private void removePreference(String value) {
        preferenceValues.remove(value);
        renderEntries();
        updatePreview();
    }

    private void removeTaboo(String value) {
        tabooValues.remove(value);
        renderEntries();
        updatePreview();
    }

    private void selectTone(String value) {
        tone = value;
        refreshToneButtons();
        updatePreview();
    }

    private void refreshToneButtons() {
        tone(directTone, DIRECT);
        tone(patientTone, PATIENT);
        tone(livelyTone, LIVELY);
    }

    private void tone(Button button, String value) {
        button.getStyleClass().remove("mc-tone-chip-on");
        if (value.equals(tone)) button.getStyleClass().add("mc-tone-chip-on");
    }

    private void renderEntries() {
        closeEntries();
        for (String value : preferenceValues) {
            MemoryChildView<javafx.scene.layout.HBox> child =
                    components.personaEntry(value, false, this::removePreference);
            preferenceViews.add(child);
            preferences.getChildren().add(child.root());
        }
        for (String value : tabooValues) {
            MemoryChildView<javafx.scene.layout.HBox> child =
                    components.personaEntry(value, true, this::removeTaboo);
            tabooViews.add(child);
            taboos.getChildren().add(child.root());
        }
    }

    private void updatePreview() {
        preview.setText(useCases.personaMarkdown(draft()));
    }

    private PersonaDraft draft() {
        return new PersonaDraft(identity.getText(), tone, preferenceValues, tabooValues);
    }

    private void closeEntries() {
        preferences.getChildren().clear();
        taboos.getChildren().clear();
        preferenceViews.forEach(MemoryChildView::close);
        tabooViews.forEach(MemoryChildView::close);
        preferenceViews.clear();
        tabooViews.clear();
    }

    @Override
    public void close() {
        saveAction.close();
        exportAction.close();
        closeEntries();
    }
}
