package com.javaclaw.api.conversation;

/** 一次会话运行的可选行为参数。 */
public record ConversationOptions(PlanProfile planProfile,
                                  com.javaclaw.framework.api.InteractionControlCommand interactionCommand) {
    public static final ConversationOptions DEFAULT = new ConversationOptions(PlanProfile.AUTO);

    public ConversationOptions {
        if (planProfile == null) planProfile = PlanProfile.AUTO;
    }

    public ConversationOptions(PlanProfile planProfile) {
        this(planProfile, null);
    }
}
