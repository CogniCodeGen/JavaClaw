package com.javaclaw.ui.javafx.plugin;

import javafx.stage.Window;

import java.nio.file.Path;
import java.util.Optional;

/** 插件 jar 选择边界；必须在 FX 线程调用，取消选择返回空值。 */
public interface PluginJarPicker {

    Optional<Path> choose(Window owner);
}
