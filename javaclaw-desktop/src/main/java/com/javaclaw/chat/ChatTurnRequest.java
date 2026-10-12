package com.javaclaw.chat;

/** Parsed composer input and its optional one-shot mode override. */
record ChatTurnRequest(String text, boolean forcePlan) {

    static ChatTurnRequest parse(String userText) {
        boolean forcePlan = userText.startsWith("/plan ")
                || userText.startsWith("/规划 ")
                || userText.startsWith("/研讨 ");
        if (!forcePlan) return new ChatTurnRequest(userText, false);
        int prefixLength = userText.startsWith("/plan ") ? 6 : 4;
        String prompt = userText.substring(prefixLength).trim();
        return prompt.isEmpty() ? null : new ChatTurnRequest(prompt, true);
    }
}
