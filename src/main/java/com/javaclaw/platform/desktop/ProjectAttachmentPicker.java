package com.javaclaw.platform.desktop;

import com.javaclaw.util.ProjectAccessPolicy;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 选择并校验聊天附件；返回值只包含当前项目内可访问的普通文件。
 *
 * <p>{@link #select(Window)} 必须由 JavaFX Application Thread 调用，会同步等待原生文件
 * 选择器关闭；用户取消时返回空结果，不抛异常。本类无可变状态，可作为 Spring 单例复用。</p>
 */
public final class ProjectAttachmentPicker {

    public Selection select(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择附件");
        chooser.setInitialDirectory(ProjectAccessPolicy.projectRoot().toFile());
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("所有支持的文件",
                        "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp",
                        "*.txt", "*.md", "*.csv", "*.json", "*.xml", "*.html", "*.css",
                        "*.java", "*.py", "*.js", "*.ts", "*.c", "*.cpp", "*.h", "*.go", "*.rs",
                        "*.log", "*.yaml", "*.yml", "*.pdf"),
                new FileChooser.ExtensionFilter("图片文件",
                        "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"),
                new FileChooser.ExtensionFilter("文档文件",
                        "*.txt", "*.md", "*.csv", "*.json", "*.xml", "*.pdf",
                        "*.java", "*.py", "*.js", "*.html"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));

        List<File> selected = chooser.showOpenMultipleDialog(owner);
        if (selected == null || selected.isEmpty()) return new Selection(List.of(), 0);

        List<File> accepted = new ArrayList<>(selected.size());
        int rejected = 0;
        for (File file : selected) {
            if (!ProjectAccessPolicy.isProjectFilePath(file.toPath())) {
                rejected++;
                continue;
            }
            accepted.add(ProjectAccessPolicy.requireProjectFilePath(file.toPath()).toFile());
        }
        return new Selection(accepted, rejected);
    }

    /** 不可变选择结果；{@code rejectedCount} 表示因项目隔离被忽略的项目。 */
    public record Selection(List<File> files, int rejectedCount) {
        public Selection {
            files = List.copyOf(files);
            if (rejectedCount < 0) throw new IllegalArgumentException("拒绝数量不能为负数");
        }
    }
}
