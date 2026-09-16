package com.javaclaw.server.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

import com.javaclaw.api.CorePayloads;
import com.javaclaw.api.CoreSchemas;
import com.javaclaw.api.ItemStatus;
import com.javaclaw.api.MessageRole;
import com.javaclaw.api.TurnId;
import com.javaclaw.protocol.CanonicalJson;

/** Core 消息读取边界；续接仅沿权威持久链接共享输入，助手输出仍绑定精确 Turn。 */
final class TurnInputMessages {
    private TurnInputMessages() {}

    static CorePayloads.Message user(Connection connection, TurnId turnId, CanonicalJson json) throws SQLException {
        TurnId original = TurnContinuationRepository.originalInputTurn(connection, turnId);
        return new ItemRepository(new TurnRepository())
                .listByTurnAndSchema(connection, original, CoreSchemas.MESSAGE).stream()
                        .map(item -> json.decode(item.payload(), CorePayloads.Message.class))
                        .filter(message -> message.role() == MessageRole.USER)
                        .findFirst()
                        .orElseThrow(() -> new PersistenceException("Turn 缺少创建时用户消息"));
    }

    static Optional<CorePayloads.Message> assistant(Connection connection, TurnId turnId, CanonicalJson json)
            throws SQLException {
        return new ItemRepository(new TurnRepository())
                .listByTurnAndSchema(connection, turnId, CoreSchemas.MESSAGE).stream()
                        .filter(item -> item.status() == ItemStatus.COMPLETED)
                        .map(item -> json.decode(item.payload(), CorePayloads.Message.class))
                        .filter(message -> message.role() == MessageRole.ASSISTANT)
                        .reduce((previous, current) -> current);
    }
}
