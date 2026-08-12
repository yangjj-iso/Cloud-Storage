package com.cloudchunk.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("cloudchunk")
            .withUsername("cloudchunk")
            .withPassword("cloudchunk");

    @Test
    void migratesAnEmptyDatabaseAndCreatesReliabilityTables() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load();

        var result = flyway.migrate();
        assertThat(result.success).isTrue();

        try (var connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM information_schema.tables
                     WHERE table_schema = DATABASE()
                       AND table_name IN ('event_outbox', 'event_inbox', 'object_gc_task')
                     """);
             var rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(3);
        }
    }
}
