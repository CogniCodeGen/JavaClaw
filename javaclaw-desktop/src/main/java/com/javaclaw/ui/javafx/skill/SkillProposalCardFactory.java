package com.javaclaw.ui.javafx.skill;

import com.javaclaw.application.skill.SkillManagementApplicationService.ProposalItem;

import java.util.function.Consumer;

/** 创建技能提案 FXML 卡片。 */
public final class SkillProposalCardFactory {
    SkillProposalCard create(ProposalItem proposal, Consumer<String> approve, Consumer<String> reject) {
        SkillProposalCard card = new SkillProposalCard();
        card.apply(proposal, approve, reject);
        return card;
    }
}
