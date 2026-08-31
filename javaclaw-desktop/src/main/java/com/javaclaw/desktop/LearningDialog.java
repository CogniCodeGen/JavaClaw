package com.javaclaw.desktop;

import java.util.List;

import javafx.scene.Node;

import com.javaclaw.sdk.model.LearningSettingsInfo;

/** Memory 与 Skill 共用的学习偏好表单；自动模式不会批准脚本、权限变化或覆盖用户内容。 */
final class LearningDialog {
    private LearningDialog() {}

    static void show(Node owner, ManagementViewModel model, String workspace) {
        model.execute("读取学习设置", sdk -> sdk.knowledge().learningSettings(workspace), settings -> {
            var mode = ManagementForms.choices(
                    List.of("OFF", "SUGGEST", "AUTO"),
                    value -> switch (value) {
                        case "OFF" -> "关闭";
                        case "AUTO" -> "自动（仅已验证的低风险新增内容）";
                        default -> "建议（需要审阅）";
                    },
                    settings.skillMode());
            var automatic = ManagementForms.check("自动提取有来源的低风险记忆", settings.memoryAutomatic());
            ManagementForms.edit(
                    owner,
                    "学习设置",
                    ManagementForms.form(
                            ManagementForms.field("Skill 学习模式", mode),
                            automatic,
                            ManagementForms.hint("冲突、用户固定条目、Persona、脚本和权限变更始终需要确认；不能自动扩大 Turn 预算。")),
                    () -> new LearningSettingsInfo(
                            workspace,
                            mode.getValue(),
                            automatic.isSelected(),
                            settings.revision(),
                            settings.updatedAt()),
                    value -> model.execute(
                            "保存学习设置",
                            sdk -> sdk.knowledge()
                                    .saveLearningSettings(
                                            value.workspaceId(),
                                            value.skillMode(),
                                            value.memoryAutomatic(),
                                            settings.revision(),
                                            ManagementViewModel.key("learning-save")),
                            ignored -> {}));
        });
    }
}
