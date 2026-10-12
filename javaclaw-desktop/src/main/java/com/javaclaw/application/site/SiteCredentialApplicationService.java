package com.javaclaw.application.site;

import java.util.List;

/**
 * 站点凭据设置页及自动化入口共享的应用服务。
 *
 * <p>实现按工作区隔离并可从任意线程调用；所有方法返回不可变快照。保存、删除和清除会话
 * 会访问数据库，必须由托管 I/O 执行器调用。操作只在事务提交成功后返回；取消通过调用线程
 * 中断协作，底层数据库提交已经完成时不会伪装成取消。快照包含明文密码，只能交给本地受信任的
 * Presentation 层，不得记录日志、发送给模型或跨越工作区传播。</p>
 */
public interface SiteCredentialApplicationService {

    Snapshot snapshot();

    Snapshot save(SaveCommand command);

    Snapshot delete(String credentialId);

    Snapshot clearSession(String credentialId);

    record Credential(
            String id,
            String name,
            String hostPattern,
            String loginUrl,
            String username,
            String password,
            String notes,
            long createdAt,
            long lastUsedAt,
            boolean hasSession) {

        public Credential {
            id = text(id);
            name = text(name);
            hostPattern = text(hostPattern);
            loginUrl = text(loginUrl);
            username = text(username);
            password = text(password);
            notes = text(notes);
        }

        public boolean hasAccount() {
            return !username.isBlank();
        }
    }

    record Snapshot(List<Credential> credentials, String storageDescription) {
        public Snapshot {
            credentials = List.copyOf(credentials == null ? List.of() : credentials);
            storageDescription = text(storageDescription);
        }

        public Credential require(String id) {
            return credentials.stream()
                    .filter(credential -> credential.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new com.javaclaw.application.error.NotFoundException(
                            "未找到站点凭据：" + id));
        }
    }

    record SaveCommand(
            String id,
            String name,
            String hostPattern,
            String loginUrl,
            String username,
            String password,
            String notes) {

        public SaveCommand {
            id = text(id);
            name = text(name);
            hostPattern = text(hostPattern);
            loginUrl = text(loginUrl);
            username = text(username);
            password = text(password);
            notes = text(notes);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
