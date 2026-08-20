package com.javaclaw.platform.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class H2DataSourceTest {

    @TempDir Path temporary;

    @Test
    void usesSingleInstanceEmbeddedFileAndPersistsAcrossConnections() throws Exception {
        DataRoot root = new DataRoot(temporary.resolve("data")).prepare();
        H2DataSource first = new H2DataSource(root);
        JdbcTemplate writer = new JdbcTemplate(first);
        writer.execute("CREATE TABLE persistence_probe (id INT PRIMARY KEY, stored_value VARCHAR(32))");
        writer.update("INSERT INTO persistence_probe VALUES (?, ?)", 1, "persisted");

        try (var connection = first.getConnection()) {
            assertFalse(connection.getMetaData().getURL().contains("AUTO_SERVER"));
        }

        H2DataSource reopened = new H2DataSource(root);
        assertEquals("persisted", new JdbcTemplate(reopened).queryForObject(
                "SELECT stored_value FROM persistence_probe WHERE id = 1", String.class));
    }
}
