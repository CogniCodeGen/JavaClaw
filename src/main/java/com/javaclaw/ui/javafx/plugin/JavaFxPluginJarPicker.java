package com.javaclaw.ui.javafx.plugin;

import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

/** 使用 JavaFX 原生文件对话框选择插件 jar；无可变状态，可由 Spring 单例复用。 */
public final class JavaFxPluginJarPicker implements PluginJarPicker {

    @Override
    public Optional<Path> choose(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择插件 jar");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("插件 jar", "*.jar"));
        File selected = chooser.showOpenDialog(owner);
        return selected == null ? Optional.empty() : Optional.of(selected.toPath());
    }
}
