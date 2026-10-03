package com.javaclaw.platform.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class H2DataSourceTest {

    @TempDir Path temporary;

    @Test
    void usesSingleInstanceEmbeddedFileAndPersistsAcrossConnections() throws Exception {
        DataRoot root = new DataRoot(temporary.resolve("data")).prepare();
        try (H2DataSource first = new H2DataSource(root)) {
            JdbcTemplate writer = new JdbcTemplate(first);
            writer.execute("CREATE TABLE persistence_probe (id INT PRIMARY KEY, stored_value VARCHAR(32))");
            writer.update("INSERT INTO persistence_probe VALUES (?, ?)", 1, "persisted");

            try (var connection = first.getConnection()) {
                assertFalse(connection.getMetaData().getURL().contains("AUTO_SERVER"));
            }
        }

        try (H2DataSource reopened = new H2DataSource(root)) {
            assertEquals("persisted", new JdbcTemplate(reopened).queryForObject(
                    "SELECT stored_value FROM persistence_probe WHERE id = 1", String.class));
        }
    }

    @Test
    void holdsDatabaseOpenBetweenBorrowedConnectionsUntilClosed() throws Exception {
        DataRoot root = new DataRoot(temporary.resolve("lifecycle")).prepare();
        H2DataSource dataSource = new H2DataSource(root);
        String jdbcUrl = null;
        try {
            try (Connection first = dataSource.getConnection()) {
                jdbcUrl = first.getMetaData().getURL();
                assertEquals(2, sessionCount(first));
            }
            try (Connection second = dataSource.getConnection()) {
                assertEquals(2, sessionCount(second));
            }
        } finally {
            dataSource.close();
        }

        dataSource.close();
        assertThrows(java.sql.SQLException.class, dataSource::getConnection);
        try (Connection reopened = DriverManager.getConnection(jdbcUrl, "sa", "")) {
            assertEquals(1, sessionCount(reopened));
        }
    }

    private static int sessionCount(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS")) {
            result.next();
            return result.getInt(1);
        }
    }
}
