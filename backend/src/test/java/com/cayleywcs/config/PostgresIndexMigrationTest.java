package com.cayleywcs.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import db.migration.V15__optimization_indexes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 在独立 schema 中制造 PG 无效索引，验证 V15 非事务重试可恢复。 */
@EnabledIfEnvironmentVariable(named = "CAYLEYWCS_PG_TEST", matches = "true")
class PostgresIndexMigrationTest {
    @Test
    void interruptedConcurrentBuildIsRecreatedAsValidIndex() throws Exception {
        String schema = "wcs_index_test_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("SPRING_DATASOURCE_URL");
        assertThat(url).startsWith("jdbc:postgresql:");
        try (Connection connection = DriverManager.getConnection(url,
                System.getenv("SPRING_DATASOURCE_USERNAME"),
                System.getenv("SPRING_DATASOURCE_PASSWORD"));
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.execute("create schema \"" + schema + "\"");
            try {
                statement.execute("set search_path to \"" + schema + "\"");
                statement.execute("create table wcs_task(id bigint,app_id bigint,priority int,is_valid boolean,status text)");
                statement.execute("create table wcs_connection_log(id bigint,app_id bigint,create_time timestamp,"
                        + "app_code text,state text,event text)");
                statement.execute("insert into wcs_task(id,app_id) values (1,1),(2,1)");
                try {
                    statement.execute("create unique index concurrently idx_wcs_task_pending_priority"
                            + " on wcs_task(app_id)");
                    throw new AssertionError("duplicate keys should abort concurrent unique index build");
                } catch (SQLException expected) {
                    assertThat(expected.getSQLState()).isEqualTo("23505");
                }
                assertThat(valid(statement, "idx_wcs_task_pending_priority")).isFalse();

                Context context = mock(Context.class);
                when(context.getConnection()).thenReturn(connection);
                V15__optimization_indexes migration = new V15__optimization_indexes();
                assertThat(migration.canExecuteInTransaction()).isFalse();
                migration.migrate(context);
                assertThat(valid(statement, "idx_wcs_task_pending_priority")).isTrue();
                assertThat(valid(statement, "idx_wcs_conn_log_state_cover")).isTrue();
            } finally {
                statement.execute("drop schema if exists \"" + schema + "\" cascade");
            }
        }
    }

    private static boolean valid(Statement statement, String name) throws SQLException {
        try (ResultSet result = statement.executeQuery("select indisvalid from pg_index "
                + "where indexrelid=to_regclass('" + name + "')")) {
            assertThat(result.next()).isTrue();
            return result.getBoolean(1);
        }
    }
}
