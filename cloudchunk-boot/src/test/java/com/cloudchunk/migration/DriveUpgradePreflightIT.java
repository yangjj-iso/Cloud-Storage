package com.cloudchunk.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DuplicateKeyException;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Before/after a real V13 upgrade: never silently rename old user data to satisfy a
 * uniqueness constraint. A dirty migration must block promotion until manually resolved.
 */
@Testcontainers(disabledWithoutDocker=true)
class DriveUpgradePreflightIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("cloudchunk").withUsername("cloudchunk").withPassword("cloudchunk");

    private static final String CONFLICT_GROUPS = """
        SELECT COUNT(*) FROM (
          SELECT user_id,parent_id,file_name COLLATE utf8mb4_unicode_ci
          FROM user_file WHERE status=0
          GROUP BY user_id,parent_id,file_name COLLATE utf8mb4_unicode_ci
          HAVING COUNT(*)>1
        ) collisions
        """;

    @Test void existingCaseFoldedDuplicatesMustBeResolvedBeforeV13() {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        var jdbc=new JdbcTemplate(ds);
        var old=Flyway.configure().dataSource(ds).locations("classpath:db/migration")
            .target("12").load();
        assertTrue(old.migrate().success);
        jdbc.update("""
            INSERT INTO user_file(id,user_id,parent_id,file_name,is_dir,status)
            VALUES (51001,51001,0,'Migration Report',1,0),
                   (51002,51001,0,'migration report',1,0),
                   (51003,51001,0,'Archived',1,1)
            """);
        assertEquals(1,jdbc.queryForObject(CONFLICT_GROUPS,Integer.class));

        var latest=Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
        assertThrows(Exception.class,latest::migrate,
            "DDL index creation must fail safely, never rename an existing user file");
        assertEquals(2,jdbc.queryForObject("""
            SELECT COUNT(*) FROM user_file WHERE id IN(51001,51002)
            """,Integer.class),"rejected migration leaves both original nodes intact");
        assertEquals(1,jdbc.queryForObject(CONFLICT_GROUPS,Integer.class));

        // An administrator/user explicitly reconciles an existing collision. The
        // application must NOT perform this step on its own.
        jdbc.update("UPDATE user_file SET file_name='Migration Report (2)' WHERE id=51002");
        assertEquals(0,jdbc.queryForObject(CONFLICT_GROUPS,Integer.class));
        latest.repair(); // MySQL DDL errors may leave a failed Flyway history row.
        assertTrue(latest.migrate().success);
        assertEquals(1,jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema=DATABASE() AND table_name='user_file'
              AND column_name='active_file_name' AND extra LIKE '%GENERATED%'
            """,Integer.class));
        assertEquals(3,jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.statistics
            WHERE table_schema=DATABASE() AND table_name='user_file'
              AND index_name='uk_drive_active_sibling' AND non_unique=0
            """,Integer.class));
        assertThrows(DuplicateKeyException.class,()->jdbc.update("""
            INSERT INTO user_file(user_id,parent_id,file_name,is_dir,status)
            VALUES (51001,0,'MIGRATION REPORT',1,0)
            """));
        // Deleted/recycled sibling doesn't reserve a live display name.
        jdbc.update("""
            INSERT INTO user_file(user_id,parent_id,file_name,is_dir,status)
            VALUES (51001,0,'MIGRATION REPORT',1,1)
            """);
    }
}
