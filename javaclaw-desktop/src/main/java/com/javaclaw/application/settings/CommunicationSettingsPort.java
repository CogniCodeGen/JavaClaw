package com.javaclaw.application.settings;

import com.javaclaw.application.settings.CommunicationSettingsApplicationService.EmailSettings;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.NotificationSettings;
import com.javaclaw.application.settings.CommunicationSettingsApplicationService.Snapshot;

/**
 * 工作区邮件与通知配置的同步持久化端口。
 *
 * <p>实现必须在返回前完成写入；失败需抛出异常，且不得把部分字段报告为成功。</p>
 */
public interface CommunicationSettingsPort {

    Snapshot load();

    void saveEmail(EmailSettings settings);

    void saveNotifications(NotificationSettings settings);
}
