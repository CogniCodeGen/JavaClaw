package com.javaclaw.application.memory;

import com.javaclaw.application.error.ValidationException;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** 记忆中心查询与编辑流程；不保存 JavaFX 页面状态。 */
public final class MemoryUseCase implements MemoryApplicationService {

    private final MemoryPort memory;

    public MemoryUseCase(MemoryPort memory) {
        this.memory = Objects.requireNonNull(memory, "memory");
    }

    @Override
    public Snapshot snapshot() {
        return memory.load();
    }

    @Override
    public OperationResult probeAndRefill() {
        String error = memory.probeEmbedding();
        int moved = 0;
        Snapshot current = memory.load();
        if ((error == null || error.isBlank()) && current.embedding().pendingCount() > 0) {
            moved = memory.promoteAllPending();
        }
        return result(moved, moved > 0 ? "已自动回填 " + moved + " 条记忆" : "");
    }

    @Override
    public OperationResult refillPending() {
        int moved = memory.promoteAllPending();
        return result(moved, moved > 0 ? "已回填 " + moved + " 条记忆" : "没有可回填的记忆");
    }

    @Override
    public com.javaclaw.memory.graph.MemoryGraph graph() {
        return memory.graph();
    }

    @Override
    public OperationResult addFact(AddFactCommand command) {
        Objects.requireNonNull(command, "command");
        String statement = required(command.text(), "事实内容");
        String section = normalized(command.section());
        memory.addFact(section.isBlank() ? "其它" : section, statement);
        return result(1, "事实已新增");
    }

    @Override
    public OperationResult editFact(EditFactCommand command) {
        Objects.requireNonNull(command, "command");
        memory.editFact(required(command.id(), "事实 ID"), required(command.text(), "事实内容"));
        return result(1, "事实已更新并重新嵌入");
    }

    @Override
    public OperationResult toggleFactPin(String factId) {
        memory.toggleFactPin(required(factId, "事实 ID"));
        return result(1, "事实置顶状态已更新");
    }

    @Override
    public OperationResult restoreFact(String factId) {
        memory.restoreFact(required(factId, "事实 ID"));
        return result(1, "事实已恢复");
    }

    @Override
    public OperationResult deleteFacts(List<String> factIds) {
        if (factIds == null || factIds.isEmpty()) throw new ValidationException("请选择要删除的事实");
        List<String> ids = factIds.stream()
                .map(id -> required(id, "事实 ID"))
                .distinct()
                .toList();
        int removed = memory.deleteFacts(ids);
        return result(removed, "已删除 " + removed + " 条事实");
    }

    @Override
    public OperationResult reindexDocument(String documentName) {
        String name = required(documentName, "文档名称");
        int rebuilt = memory.reindexDocument(name);
        return result(rebuilt, "已重建 " + rebuilt + " 个分块的索引");
    }

    @Override
    public OperationResult deleteDocument(String documentName) {
        String name = required(documentName, "文档名称");
        int removed = memory.deleteDocument(name);
        return result(removed, "已删除文档「" + name + "」");
    }

    @Override
    public OperationResult savePersona(PersonaDraft persona) {
        PersonaDraft checked = persona(persona);
        memory.savePersona(checked);
        return result(1, "人格已保存（下一轮对话生效）");
    }

    @Override
    public String personaMarkdown(PersonaDraft persona) {
        return memory.personaMarkdown(Objects.requireNonNull(persona, "persona"));
    }

    @Override
    public void exportPersona(Path target, PersonaDraft persona) {
        memory.exportPersona(Objects.requireNonNull(target, "target"), personaMarkdown(persona));
    }

    @Override
    public OperationResult revokeCorrection(String correctionId) {
        memory.revokeCorrection(required(correctionId, "纠错 ID"));
        return result(1, "纠错已撤销");
    }

    @Override
    public OperationResult deleteCorrection(String correctionId) {
        memory.deleteCorrection(required(correctionId, "纠错 ID"));
        return result(1, "纠错记录已删除");
    }

    private OperationResult result(int affected, String message) {
        return new OperationResult(memory.load(), affected, message);
    }

    private static PersonaDraft persona(PersonaDraft draft) {
        if (draft == null) throw new ValidationException("人格内容不能为空");
        if (draft.identity().isBlank() && draft.preferences().isEmpty() && draft.taboos().isEmpty()) {
            throw new ValidationException("人格身份、偏好和禁忌不能同时为空");
        }
        return draft;
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
