package com.javaclaw.ui.javafx.diagnostics;

import javafx.stage.Window;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 诊断包目标选择边界。实现必须在 FX 线程调用；用户取消返回空值且不视为失败。
 */
public interface DiagnosticsExportTargetPicker {

    Optional<Path> choose(Window owner);
}
