package com.javaclaw.desktop;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import javafx.scene.Node;

/** Desktop 与宿主窗口交互的单一端口。生产实现使用 JavaFX，对话测试可提供确定性实现，避免绕过 SDK 或依赖真实系统文件选择器。 */
interface DesktopDialogGateway {
    /** 在所属窗口选择一个目录；取消返回空。 */
    Optional<Path> chooseDirectory(Node owner, String title);

    /** 在所属窗口选择一个符合类型约束的文件；取消返回空。 */
    Optional<Path> chooseOpenFile(Node owner, String title, List<FileType> types);

    /**
     * 在所属窗口选择多个符合类型约束的文件；取消返回空列表。
     *
     * <p>默认实现保留单文件测试替身的兼容性；生产实现应使用宿主的多选文件对话框。
     */
    default List<Path> chooseOpenFiles(Node owner, String title, List<FileType> types) {
        return chooseOpenFile(owner, title, types).stream().toList();
    }

    /** 在所属窗口选择保存目标；取消返回空，调用方仍负责覆盖策略。 */
    Optional<Path> chooseSaveFile(Node owner, String title, String suggestedName, List<FileType> types);

    /**
     * 显示需要用户明确确认的操作。
     *
     * @param acceptText 非空确认按钮文案，用于区分普通确认和危险操作
     * @return 仅当用户选择确认按钮时返回 true
     */
    boolean confirm(Node owner, String title, String header, String message, String acceptText);

    /** 显示单行文本输入；取消与空文本分别由 Optional 和字符串值表达。 */
    Optional<String> promptText(Node owner, String title, String header, String initialValue);

    /** 显示有限选项输入；取消返回空。 */
    Optional<String> choose(Node owner, String title, String header, List<String> choices);

    /**
     * 处理离开编辑资源前的未保存修改。
     *
     * <p>默认实现复用有限选项端口，保证已有测试替身仍可工作；生产实现使用三个语义明确的按钮。
     *
     * @param owner 所属窗口中的节点
     * @param resource 当前编辑资源的用户可读名称
     * @return 保存、放弃或取消离开的明确决定
     */
    default UnsavedDecision resolveUnsavedChanges(Node owner, String resource) {
        String save = "保存并继续";
        String discard = "放弃修改";
        return choose(
                        owner,
                        "有未保存的修改",
                        "“" + (resource == null || resource.isBlank() ? "当前内容" : resource) + "”尚未保存。",
                        List.of(save, discard))
                .map(value -> save.equals(value) ? UnsavedDecision.SAVE : UnsavedDecision.DISCARD)
                .orElse(UnsavedDecision.CANCEL);
    }

    /** 打开已由调用方校验的外部 HTTPS 地址；不能接受任意系统命令。 */
    void openExternal(URI uri);

    /** 离开编辑资源时可选择的三种结果。 */
    enum UnsavedDecision {
        SAVE,
        DISCARD,
        CANCEL
    }

    /**
     * 文件选择器展示类型。
     *
     * @param description 非空用户展示名
     * @param patterns 非空扩展名模式，例如 {@code *.md}
     */
    record FileType(String description, List<String> patterns) {
        public FileType {
            description = description == null ? "文件" : description.strip();
            patterns = patterns == null ? List.of() : List.copyOf(patterns);
            if (description.isEmpty() || patterns.isEmpty()) {
                throw new IllegalArgumentException("文件类型必须包含展示名和至少一个扩展模式");
            }
        }
    }
}
