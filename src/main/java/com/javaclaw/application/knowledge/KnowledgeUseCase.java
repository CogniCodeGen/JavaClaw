package com.javaclaw.application.knowledge;

import com.javaclaw.application.error.NotFoundException;
import com.javaclaw.application.error.ValidationException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 知识库查询、导入与索引维护流程；不保存 JavaFX 页面状态。 */
public final class KnowledgeUseCase implements KnowledgeApplicationService {

    public static final long MAX_IMPORT_FILE_SIZE = 50L * 1024 * 1024;

    private final KnowledgePort knowledge;
    private final KnowledgeSettingsPort settings;
    private final String workspaceName;

    public KnowledgeUseCase(
            KnowledgePort knowledge,
            KnowledgeSettingsPort settings,
            String workspaceName) {
        this.knowledge = Objects.requireNonNull(knowledge, "knowledge");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.workspaceName = workspaceName == null || workspaceName.isBlank()
                ? "默认工作区" : workspaceName.strip();
    }

    @Override
    public Snapshot snapshot() {
        return new Snapshot(knowledge.enabled(), knowledge.initializationError(), workspaceName,
                settings.load(), knowledge.health(), knowledge.documents());
    }

    @Override
    public SearchResult search(String query) {
        String checked = required(query, "检索内容");
        return new SearchResult(checked,
                knowledge.search(checked, Math.max(1, settings.load().retrieveLimit())));
    }

    @Override
    public ImportResult importFiles(List<Path> files, Scope scope) {
        if (files == null || files.isEmpty()) throw new ValidationException("请选择要导入的文件");
        Scope target = importScope(scope);
        int succeeded = 0;
        List<String> failures = new ArrayList<>();
        for (Path file : files.stream().filter(Objects::nonNull).distinct().toList()) {
            String failure = validateFile(file);
            if (failure.isBlank()) {
                if (knowledge.importFile(file, target)) succeeded++;
                else failure = "导入失败";
            }
            if (!failure.isBlank()) failures.add(file.getFileName() + "：" + failure);
        }
        return new ImportResult(snapshot(), succeeded, failures.size(), failures);
    }

    @Override
    public ImportResult importText(String title, String text, Scope scope) {
        String content = required(text, "文本内容");
        String resolvedTitle = normalized(title).isBlank() ? "手动导入文本" : title.strip();
        boolean success = knowledge.importText(resolvedTitle, content, importScope(scope));
        return new ImportResult(snapshot(), success ? 1 : 0, success ? 0 : 1,
                success ? List.of() : List.of("文本导入失败"));
    }

    @Override
    public Snapshot setDocumentEnabled(String documentName, boolean enabled) {
        String name = requireDocument(documentName);
        knowledge.setDocumentEnabled(name, enabled);
        return snapshot();
    }

    @Override
    public Snapshot setAllEnabled(boolean enabled, Scope scope) {
        knowledge.setAllEnabled(enabled, scope == null || scope == Scope.ALL ? null : scope);
        return snapshot();
    }

    @Override
    public Snapshot deleteDocument(String documentName) {
        String name = requireDocument(documentName);
        int removed = knowledge.deleteDocument(name);
        if (removed < 1) throw new NotFoundException("未找到知识库文档：" + name);
        knowledge.setDocumentEnabled(name, true);
        return snapshot();
    }

    @Override
    public Snapshot clear() {
        knowledge.clear();
        return snapshot();
    }

    @Override
    public ReindexResult rebuildIndex() {
        int rebuilt = knowledge.rebuildIndex();
        return new ReindexResult(snapshot(), rebuilt);
    }

    @Override
    public Settings saveChunkSettings(int chunkSize, int chunkOverlap) {
        if (chunkSize < 128 || chunkSize > 1024) {
            throw new ValidationException("分块大小必须在 128 到 1024 之间");
        }
        if (chunkOverlap < 0 || chunkOverlap > 256 || chunkOverlap >= chunkSize) {
            throw new ValidationException("片段重叠必须小于分块大小且不超过 256");
        }
        return settings.saveChunkSettings(chunkSize, chunkOverlap);
    }

    @Override
    public AutoCloseable observeHealth(HealthListener listener) {
        return knowledge.observeHealth(Objects.requireNonNull(listener, "listener"));
    }

    private String requireDocument(String name) {
        String checked = required(name, "文档名称");
        boolean exists = knowledge.documents().stream()
                .anyMatch(document -> document.name().equals(checked));
        if (!exists) throw new NotFoundException("未找到知识库文档：" + checked);
        return checked;
    }

    private static Scope importScope(Scope scope) {
        return scope == Scope.GLOBAL ? Scope.GLOBAL : Scope.WORKSPACE;
    }

    private static String validateFile(Path file) {
        if (!Files.isRegularFile(file)) return "不是可读取的普通文件";
        try {
            if (Files.size(file) > MAX_IMPORT_FILE_SIZE) return "文件过大（最大 50MB）";
        } catch (java.io.IOException failure) {
            return "无法读取文件大小：" + failure.getMessage();
        }
        return "";
    }

    private static String required(String value, String label) {
        String checked = normalized(value);
        if (checked.isBlank()) throw new ValidationException(label + "不能为空");
        return checked;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.strip();
    }
}
