package com.javaclaw.ui.javafx.site;

import com.javaclaw.application.site.SiteCredentialApplicationService.Credential;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 单个站点凭据卡片的派生展示状态。 */
public final class SiteCredentialCardViewModel {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final StringProperty name = new SimpleStringProperty("");
    private final StringProperty session = new SimpleStringProperty("");
    private final StringProperty host = new SimpleStringProperty("");
    private final StringProperty account = new SimpleStringProperty("");
    private final StringProperty timestamps = new SimpleStringProperty("");
    private final StringProperty notes = new SimpleStringProperty("");
    private final BooleanProperty hasSession = new SimpleBooleanProperty();
    private final BooleanProperty timestampsVisible = new SimpleBooleanProperty();
    private final BooleanProperty notesVisible = new SimpleBooleanProperty();

    public StringProperty nameProperty() { return name; }
    public StringProperty sessionProperty() { return session; }
    public StringProperty hostProperty() { return host; }
    public StringProperty accountProperty() { return account; }
    public StringProperty timestampsProperty() { return timestamps; }
    public StringProperty notesProperty() { return notes; }
    public BooleanProperty hasSessionProperty() { return hasSession; }
    public BooleanProperty timestampsVisibleProperty() { return timestampsVisible; }
    public BooleanProperty notesVisibleProperty() { return notesVisible; }

    public void apply(Credential credential) {
        name.set(credential.name());
        hasSession.set(credential.hasSession());
        session.set(credential.hasSession() ? "● 已保存会话" : "○ 未登录");
        host.set("主机匹配: " + credential.hostPattern());
        account.set(credential.hasAccount()
                ? "用户名: " + credential.username() + "    密码: ********"
                : "登录方式: 浏览器会话（未保存账号密码）");
        StringBuilder time = new StringBuilder();
        if (credential.createdAt() > 0) {
            time.append("创建于 ").append(TIME.format(Instant.ofEpochMilli(credential.createdAt())));
        }
        if (credential.lastUsedAt() > 0) {
            if (!time.isEmpty()) time.append("  ·  ");
            time.append("最近使用 ").append(TIME.format(Instant.ofEpochMilli(credential.lastUsedAt())));
        }
        timestamps.set(time.toString());
        timestampsVisible.set(!time.isEmpty());
        notes.set(credential.notes().isBlank() ? "" : "备注: " + credential.notes());
        notesVisible.set(!credential.notes().isBlank());
    }
}
