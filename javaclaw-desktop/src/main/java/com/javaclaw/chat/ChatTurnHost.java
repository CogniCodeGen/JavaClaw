package com.javaclaw.chat;

import javafx.stage.Window;

import java.io.File;
import java.util.List;

/** 对话轮次对会话与消息视图的窄端口；不持有运行时或数据库。 */
interface ChatTurnHost {
    ChatSession currentSession();
    Window ownerWindow();
    void addUserMessage(String text, List<File> attachments);
    void addStaticMessage(ChatMessage.Role role, String text);
    void saveChatHistory();
    void storeAssistantMessage(ChatSession target, ChatMessage message);
    void storeSystemMessage(ChatSession target, String text);
    String lastUserMessage();
    void deleteAssistantMessage(AssistantMessageView message);
    void adoptAssistantMessage(ChatMessage message);
    void appendClarification(String reason, String question);
    void disposeSuspendedStreamNodes();
    void finishBackgroundStream(ChatSession finishedSession);
}
