package com.javaclaw.ui.javafx.loop;

import com.javaclaw.loop.model.Decision;
import com.javaclaw.loop.model.LoopStatus;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * 循环状态面板：在聊天流里就地渲染 {@code loop_status} 事件（{@link LoopStatus}）。
 *
 * <p>整个循环运行期间复用同一个实例，每轮收到状态就 {@link #update(LoopStatus)} 原地刷新——
 * 轮次、决策徽章、已满足准则进度、用量、说明。颜色全部走 chat.css 的 {@code -jc-*} 令牌
 * （随主题换肤），决策语义色（完成绿/停止红）为跨主题固定的状态色。</p>
 */
public final class LoopStatusView extends VBox {

    private final Label iterationLabel = new Label();
    private final Label badge = new Label();
    private final ProgressBar criteriaBar = new ProgressBar(0);
    private final Label criteriaLabel = new Label();
    private final HBox criteriaRow;
    private final Label reasonLabel = new Label();
    private final Label tokensLabel = new Label();

    public LoopStatusView() {
        getStyleClass().add("loop-status-card");
        setSpacing(8);
        setPadding(new Insets(12, 14, 12, 14));

        // 顶部：标题 + 轮次 + 决策徽章
        Label title = new Label("🔁 循环");
        title.getStyleClass().add("loop-status-title");
        iterationLabel.getStyleClass().add("loop-status-iter");
        badge.getStyleClass().add("loop-badge");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(8, title, iterationLabel, spacer, badge);
        top.setAlignment(Pos.CENTER_LEFT);

        // 准则进度：仅当存在结构化准则时显示
        criteriaBar.getStyleClass().add("loop-progress");
        criteriaBar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(criteriaBar, Priority.ALWAYS);
        criteriaLabel.getStyleClass().add("loop-status-meta");
        criteriaLabel.setMinWidth(Region.USE_PREF_SIZE);
        criteriaRow = new HBox(8, criteriaBar, criteriaLabel);
        criteriaRow.setAlignment(Pos.CENTER_LEFT);
        criteriaRow.setManaged(false);
        criteriaRow.setVisible(false);

        // 底部：说明 + 用量
        reasonLabel.getStyleClass().add("loop-status-reason");
        reasonLabel.setWrapText(true);
        tokensLabel.getStyleClass().add("loop-status-meta");

        getChildren().addAll(top, criteriaRow, reasonLabel, tokensLabel);
    }

    /** 原地刷新为最新状态。 */
    public void update(LoopStatus status) {
        if (status == null) {
            return;
        }
        iterationLabel.setText("第 " + status.iteration() + " 轮");

        applyBadge(status.decision());

        if (status.total() > 0) {
            criteriaRow.setManaged(true);
            criteriaRow.setVisible(true);
            criteriaBar.setProgress((double) status.satisfied() / status.total());
            criteriaLabel.setText("已满足 " + status.satisfied() + "/" + status.total());
        } else {
            criteriaRow.setManaged(false);
            criteriaRow.setVisible(false);
        }

        String reason = status.reason();
        reasonLabel.setText(reason == null ? "" : reason);
        reasonLabel.setManaged(reason != null && !reason.isBlank());
        reasonLabel.setVisible(reason != null && !reason.isBlank());

        // 等待透明度（仿 ScheduleWakeup 的 reason 惯例）：告诉用户下一轮几秒后开
        String waiting = status.nextDelaySeconds() > 0
                ? " · ⏳ " + status.nextDelaySeconds() + "s 后开下一轮"
                : "";
        tokensLabel.setText("累计用量 " + status.tokensUsed() + " tokens" + waiting);
    }

    /**
     * 就地定格为「已停止」终态。
     *
     * <p>供 UI 在用户主动停止时<b>同步</b>调用：控制器随后发出的 CANCELLED 终态事件
     * 会因流代次已递增而被丢弃，不定格的话卡片将永远显示「进行中/⏳ 等下一轮」。</p>
     */
    public void markCancelled() {
        applyBadge(Decision.STOP);
        reasonLabel.setText("用户取消");
        reasonLabel.setManaged(true);
        reasonLabel.setVisible(true);
        // 去掉「N 秒后开下一轮」等待后缀，避免与已停止的事实矛盾
        String text = tokensLabel.getText();
        int idx = text == null ? -1 : text.indexOf(" · ⏳");
        if (idx >= 0) {
            tokensLabel.setText(text.substring(0, idx));
        }
    }

    /** 徽章文案 + 语义色样式类。 */
    private record BadgeSpec(String text, String styleClass) {}

    /**
     * 按决策切换徽章文案与语义色样式类。
     *
     * <p>{@link LoopStatus#decision()} 直接携带 {@link Decision} 枚举（不再经 name() 字符串
     * 通道反解析），配合<b>穷尽</b> switch 表达式：枚举重命名/新增值时生产端与本消费端
     * 均由编译器兜底，不存在匹配静默失灵、终态循环永远显示「进行中」的通道。</p>
     */
    private void applyBadge(Decision decision) {
        badge.getStyleClass().removeAll("loop-badge-run", "loop-badge-done", "loop-badge-stop");
        BadgeSpec spec = switch (decision == null ? Decision.CONTINUE : decision) {
            case DONE -> new BadgeSpec("已完成", "loop-badge-done");
            case STOP -> new BadgeSpec("已停止", "loop-badge-stop");
            case CONTINUE -> new BadgeSpec("进行中", "loop-badge-run");
        };
        badge.setText(spec.text());
        badge.getStyleClass().add(spec.styleClass());
    }
}
