package com.javaclaw.ui.javafx.knowledge;

import javafx.stage.Window;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Native file-selection boundary used by the knowledge documents controller. */
public interface KnowledgeImportPicker {
    List<Path> chooseFiles(Window owner);
    Optional<Path> chooseDirectory(Window owner);
}
