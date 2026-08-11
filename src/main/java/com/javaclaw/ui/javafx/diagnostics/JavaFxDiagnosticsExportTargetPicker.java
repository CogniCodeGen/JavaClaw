package com.javaclaw.ui.javafx.diagnostics;

import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;

/** 使用 JavaFX 原生保存对话框选择诊断包目标；无可变状态，可由 Spring 单例复用。 */
public final class JavaFxDiagnosticsExportTargetPicker
        implements DiagnosticsExportTargetPicker {

    @Override
    public Optional<Path> choose(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("保存诊断包");
        chooser.setInitialFileName("javaclaw-diagnostics-" + LocalDate.now() + ".zip");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("诊断 Zip (*.zip)", "*.zip"));
        File target = chooser.showSaveDialog(owner);
        return target == null ? Optional.empty() : Optional.of(target.toPath());
    }
}
