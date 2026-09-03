package com.javaclaw.desktop.settings;

/** 确定性初始化资源已经存在但内容不符合当前预设。 */
final class AgentPresetOnboardingConflictException extends IllegalStateException {
    AgentPresetOnboardingConflictException(String message) {
        super(message);
    }
}
