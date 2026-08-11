package com.javaclaw.ui.javafx.knowledge;

import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** JavaFX native chooser implementation; it never reads selected file contents. */
public final class JavaFxKnowledgeImportPicker implements KnowledgeImportPicker {

    @Override
    public List<Path> chooseFiles(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择要导入到知识库的文件");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("支持的文档格式",
                        "*.txt", "*.text", "*.md", "*.markdown", "*.log", "*.csv",
                        "*.json", "*.xml", "*.html", "*.htm", "*.pdf"),
                new FileChooser.ExtensionFilter("PDF 文件", "*.pdf"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));
        var selected = chooser.showOpenMultipleDialog(owner);
        return selected == null ? List.of()
                : selected.stream().map(java.io.File::toPath).toList();
    }

    @Override
    public Optional<Path> chooseDirectory(Window owner) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择要导入的目录");
        java.io.File selected = chooser.showDialog(owner);
        return selected == null ? Optional.empty() : Optional.of(selected.toPath());
    }
}
