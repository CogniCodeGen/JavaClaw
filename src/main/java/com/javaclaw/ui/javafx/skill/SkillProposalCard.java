package com.javaclaw.ui.javafx.skill;

import com.javaclaw.application.skill.SkillManagementApplicationService.ProposalItem;
import com.javaclaw.platform.fxml.EmbeddedFxmlLoader;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Consumer;

/** 待审提案复用卡片；模板只加载一次，动作通过稳定 ID 回调宿主 Controller。 */
public final class SkillProposalCard extends VBox {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault());

    @FXML private VBox root;
    @FXML private Label actionBadge;
    @FXML private Label titleLabel;
    @FXML private Label timeLabel;
    @FXML private Label warningLabel;
    @FXML private Label reasonLabel;
    @FXML private TextArea previewArea;

    private ProposalItem proposal;
    private Consumer<String> approve = ignored -> {};
    private Consumer<String> reject = ignored -> {};

    SkillProposalCard() {
        VBox loaded = EmbeddedFxmlLoader.load(
                SkillProposalCard.class.getResource("/fxml/skill/skill-proposal-card.fxml"),
                this, VBox.class);
        if (loaded != root) throw new IllegalStateException("技能提案卡片 FXML 根节点不一致");
        getChildren().setAll(root);
    }

    void apply(ProposalItem value, Consumer<String> approve, Consumer<String> reject) {
        proposal = Objects.requireNonNull(value, "value");
        this.approve = Objects.requireNonNull(approve, "approve");
        this.reject = Objects.requireNonNull(reject, "reject");
        boolean create = "create".equalsIgnoreCase(value.action());
        actionBadge.setText("[" + value.action() + "]");
        actionBadge.getStyleClass().removeAll("jc-badge-running", "jc-badge-indigo");
        actionBadge.getStyleClass().add(create ? "jc-badge-running" : "jc-badge-indigo");
        titleLabel.setText("🤖 " + value.skillName());
        timeLabel.setText(TIME.format(Instant.ofEpochMilli(value.createdAt())));
        warningLabel.setVisible(value.userModifiedWarning());
        warningLabel.setManaged(value.userModifiedWarning());
        reasonLabel.setText("理由：" + (value.reason().isBlank() ? "（无）" : value.reason()));
        previewArea.setText(value.preview());
    }

    @FXML private void approveRequested() { if (proposal != null) approve.accept(proposal.id()); }
    @FXML private void rejectRequested() { if (proposal != null) reject.accept(proposal.id()); }
}
