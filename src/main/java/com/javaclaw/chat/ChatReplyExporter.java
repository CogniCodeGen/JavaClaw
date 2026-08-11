package com.javaclaw.chat;

import com.javaclaw.platform.execution.TaskScope;
import com.javaclaw.platform.execution.TaskSpec;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.util.Objects;
import java.util.function.Supplier;

/** Chooses a destination on the FX thread and writes assistant replies off-thread. */
final class ChatReplyExporter {

    private static final Logger log = LoggerFactory.getLogger(ChatReplyExporter.class);

    private final TaskScope tasks;
    private final Supplier<Window> owner;

    ChatReplyExporter(TaskScope tasks, Supplier<Window> owner) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    void save(String text) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("保存回复到文件");
        chooser.setInitialFileName("reply.md");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Markdown", "*.md"),
                new FileChooser.ExtensionFilter("文本文件", "*.txt"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));
        File file = chooser.showSaveDialog(owner.get());
        if (file == null) return;
        tasks.submit(TaskSpec.io("export-assistant-reply"), context -> {
            Files.writeString(file.toPath(), text);
            return null;
        }).completion().whenComplete((ignored, failure) -> {
            if (failure != null) log.error("保存回复到文件失败", failure);
        });
    }
}
