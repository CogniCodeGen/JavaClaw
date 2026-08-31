package com.javaclaw.desktop;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javafx.animation.PauseTransition;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.util.Duration;

import com.javaclaw.sdk.model.ProfileInfo;
import com.javaclaw.sdk.model.ScheduleInfo;

/** 原定时任务的列表与详情表单；H2 权威，触发结果明确区分 SKIP、已启动和执行完成。 */
final class SchedulePane extends ManagedManagementPage {
    private final String workspace;
    private final ListView<ScheduleInfo> schedules = ManagementForms.list(
            value -> value.name() + "\n" + (value.enabled() ? "已启用" : "已停用") + " · 下次 "
                    + DesktopPresentationMapper.instant(value.nextFireAt(), "待计算"),
            "尚无定时任务",
            "创建后可校验 Cron、预览下次触发时间并查看执行历史。");
    private final BorderPane detail = new BorderPane();
    private List<ProfileInfo> profiles = List.of();

    SchedulePane(ManagementViewModel model) {
        super(model);
        workspace = model.workspaceId();
        setCenter(ManagementForms.split(schedules, detail));
        setTop(ManagementForms.actions(
                ManagementForms.command(
                        "＋ 定时任务",
                        UiActionKind.PRIMARY,
                        model,
                        () -> requestNavigation(() -> edit(new ScheduleInfo(
                                null,
                                "新定时任务",
                                workspace,
                                null,
                                profiles.isEmpty() ? "" : profiles.getFirst().id(),
                                "",
                                "0 0 9 * * ?",
                                java.time.ZoneId.systemDefault().getId(),
                                false,
                                null,
                                null,
                                "",
                                0,
                                null,
                                null)))),
                ManagementForms.command("刷新", UiActionKind.GHOST, model, () -> requestNavigation(this::reload))));
        guardSelection(schedules, this::edit);
        detail.setCenter(ManagementForms.emptyState("◷", "创建定时任务", "同一任务绑定稳定 Thread，重叠时跳过且错过不补跑；无人值守不能获得宿主完全访问权限。"));
        reload();
    }

    private void reload() {
        refreshing();
        String selectedId = schedules.getSelectionModel().getSelectedItem() == null
                ? null
                : schedules.getSelectionModel().getSelectedItem().id();
        model.execute(
                "读取定时任务",
                sdk -> sdk.models().listProfiles().thenCombine(sdk.automations().listSchedules(), ScheduleCatalog::new),
                catalog -> {
                    profiles = catalog.profiles().stream()
                            .filter(value -> "SCHEDULE".equals(value.kind()))
                            .toList();
                    var filtered = catalog.schedules().stream()
                            .filter(value -> workspace.equals(value.workspaceId()))
                            .toList();
                    schedules.getItems().setAll(filtered);
                    var selected = filtered.stream()
                            .filter(value -> value.id().equals(selectedId))
                            .findFirst()
                            .orElse(filtered.isEmpty() ? null : filtered.getFirst());
                    if (selected != null) {
                        schedules.getSelectionModel().select(selected);
                    }
                    ready();
                },
                ignored -> loadFailed());
    }

    private void edit(ScheduleInfo original) {
        var name = ManagementForms.text(original.name(), "名称");
        var prompt = ManagementForms.area(original.prompt(), 8);
        var cron = ManagementForms.text(original.cronExpression(), "Quartz cron");
        var zone = ManagementForms.text(original.zoneId(), "IANA 时区，如 Asia/Shanghai");
        var enabled = ManagementForms.check("启用定时触发", original.enabled());
        var profile = ManagementForms.choices(
                profiles,
                ProfileInfo::name,
                profiles.stream()
                        .filter(value -> value.id().equals(original.profileId()))
                        .findFirst()
                        .orElse(null));
        var preview = ManagementForms.hint("正在校验未来五次触发时间…");
        var previewDelay = new PauseTransition(Duration.millis(280));
        var previewRevision = new AtomicLong();
        Runnable requestPreview = () -> {
            long accepted = previewRevision.incrementAndGet();
            String validation = validateCronAndZone(cron.getText(), zone.getText());
            if (validation != null) {
                previewDelay.stop();
                preview.setText("表达式预览：" + validation);
                return;
            }
            preview.setText("正在校验未来五次触发时间…");
            model.execute(
                    "预览定时任务",
                    sdk -> sdk.automations().previewSchedule(cron.getText(), zone.getText(), 5),
                    value -> {
                        if (accepted == previewRevision.get()) {
                            preview.setText(formatPreview(value.zoneId(), value.fireTimes()));
                        }
                    },
                    failure -> {
                        if (accepted == previewRevision.get()) {
                            preview.setText("预览失败：" + java.util.Objects.toString(failure.getMessage(), "服务不可用"));
                        }
                    });
        };
        previewDelay.setOnFinished(ignored -> requestPreview.run());
        var refreshPreview = (javafx.beans.value.ChangeListener<String>) (ignored, old, value) -> {
            previewDelay.stop();
            previewDelay.playFromStart();
        };
        cron.textProperty().addListener(refreshPreview);
        zone.textProperty().addListener(refreshPreview);
        var save = ManagementForms.command("保存", UiActionKind.PRIMARY, model, () -> {
            String invalid =
                    validate(name.getText(), prompt.getText(), cron.getText(), zone.getText(), profile.getValue());
            if (invalid != null) {
                model.validationError(invalid);
                return;
            }
            var value = new ScheduleInfo(
                    original.id(),
                    name.getText(),
                    workspace,
                    original.threadId(),
                    profile.getValue().id(),
                    prompt.getText(),
                    cron.getText(),
                    zone.getText(),
                    enabled.isSelected(),
                    original.nextFireAt(),
                    original.lastFireAt(),
                    original.lastResult(),
                    original.revision(),
                    original.createdAt(),
                    original.updatedAt());
            model.execute(
                    "校验并保存定时任务",
                    sdk -> sdk.automations()
                            .putSchedule(value, original.revision(), ManagementViewModel.key("schedule-save")),
                    saved -> {
                        saveSucceeded();
                        edit(saved);
                        reload();
                    },
                    this::saveFailed);
        });
        var trigger =
                ManagementForms.command("立即触发已保存版本", UiActionKind.SECONDARY, model, () -> runSchedule(original.id()));
        var history = ManagementForms.button("执行历史", UiActionKind.GHOST, () -> {
            if (original.threadId() != null) {
                model.showThread(original.threadId());
            }
        });
        var delete = ManagementForms.command("删除任务", UiActionKind.DANGER, model, () -> {
            if (ManagementForms.confirm(this, model, "删除定时任务", "停止后续触发，保留执行历史；已经运行的 Turn 不会伪装为已撤回。")) {
                model.execute(
                        "删除定时任务",
                        sdk -> sdk.automations()
                                .deleteSchedule(
                                        original.id(), original.revision(), ManagementViewModel.key("schedule-delete")),
                        ignored -> {
                            detail.setCenter(ManagementForms.emptyState("✓", "定时任务已删除", "执行历史仍保留在原 Thread 中。"));
                            reload();
                        });
            }
        });
        for (var button : List.of(trigger, delete)) {
            button.setVisible(original.id() != null);
            button.setManaged(original.id() != null);
        }
        history.setDisable(original.threadId() == null);
        var fields = ManagementForms.form(
                ManagementForms.field("名称", name),
                ManagementForms.field("执行 Profile", profile),
                ManagementForms.field("任务", prompt),
                ManagementForms.section(
                        "触发规则",
                        ManagementForms.field("Cron 表达式", cron),
                        ManagementForms.field("时区", zone),
                        enabled,
                        preview),
                ManagementForms.hint("上次触发："
                        + DesktopPresentationMapper.instant(original.lastFireAt(), "尚未触发") + "\n结果："
                        + (original.lastResult() == null
                                        || original.lastResult().isBlank()
                                ? "暂无结果"
                                : original.lastResult())));
        editSession(original.id() == null ? "新定时任务" : original.name(), fields, save, this::reload);
        detail.setCenter(ManagementForms.editor(fields, save, trigger, history, delete));
        requestPreview.run();
    }

    private void runSchedule(String id) {
        Runnable trigger = () -> model.execute(
                "触发任务",
                sdk -> sdk.automations().triggerSchedule(id, ManagementViewModel.key("schedule-trigger")),
                value -> {
                    if (value.skipped()) {
                        ManagementForms.showText(this, "本次未启动", value.reason());
                    } else if (value.turn() != null) {
                        model.showThread(value.turn().threadId());
                    }
                    reload();
                });
        if (!dirtyProperty().get()) {
            trigger.run();
            return;
        }
        model.dialogs()
                .choose(this, "运行定时任务", "当前表单有未保存修改。", List.of("保存并运行", "运行已保存版本"))
                .ifPresent(choice -> {
                    if ("保存并运行".equals(choice)) {
                        requestSave(success -> {
                            if (success) {
                                trigger.run();
                            }
                        });
                    } else {
                        trigger.run();
                    }
                });
    }

    private static String formatPreview(String zoneId, List<java.time.Instant> fireTimes) {
        var formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.of(zoneId));
        return "未来五次触发（" + zoneId + "）：\n"
                + fireTimes.stream().map(formatter::format).collect(java.util.stream.Collectors.joining("\n"));
    }

    static String validate(String name, String prompt, String cron, String zone, ProfileInfo profile) {
        if (name == null || name.isBlank()) {
            return "名称不能为空";
        }
        if (profile == null) {
            return "请选择执行 Profile";
        }
        if (prompt == null || prompt.isBlank()) {
            return "任务内容不能为空";
        }
        return validateCronAndZone(cron, zone);
    }

    static String validateCronAndZone(String cron, String zone) {
        int fields = cron == null || cron.isBlank() ? 0 : cron.strip().split("\\s+").length;
        if (fields != 6 && fields != 7) {
            return "Quartz Cron 应包含 6 或 7 个字段";
        }
        try {
            ZoneId.of(zone == null ? "" : zone.strip());
        } catch (RuntimeException invalid) {
            return "请输入有效 IANA 时区，例如 Asia/Shanghai";
        }
        return null;
    }

    private record ScheduleCatalog(List<ProfileInfo> profiles, List<ScheduleInfo> schedules) {}
}
