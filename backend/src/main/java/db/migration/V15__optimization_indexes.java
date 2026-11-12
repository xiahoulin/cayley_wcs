package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * PostgreSQL 在线建索引，H2 使用同等查询前缀的普通索引以保持迁移测试可运行。
 * PG 的 partial / INCLUDE 语法与 H2 不兼容，故集中在一个带方言分支的迁移中。
 */
public class V15__optimization_indexes extends BaseJavaMigration {
    @Override
    public boolean canExecuteInTransaction() {
        return false; // PostgreSQL CREATE INDEX CONCURRENTLY 要求独立自动提交语句。
    }

    @Override
    public void migrate(Context context) throws Exception {
        String database = context.getConnection().getMetaData().getDatabaseProductName();
        try (Statement statement = context.getConnection().createStatement()) {
            if ("PostgreSQL".equals(database)) {
                createPostgresIndex(context.getConnection(), "idx_wcs_task_pending_priority", """
                        create index concurrently if not exists "idx_wcs_task_pending_priority"
                        on "wcs_task" ("app_id", "priority" desc, "id" asc)
                        where "is_valid" = true and "status" = 'pending'
                        """);
                createPostgresIndex(context.getConnection(), "idx_wcs_conn_log_state_cover", """
                        create index concurrently if not exists "idx_wcs_conn_log_state_cover"
                        on "wcs_connection_log" ("app_id", "create_time", "id")
                        include ("app_code", "state") where "event" = 'STATE_CHANGE'
                        """);
            } else if ("H2".equals(database)) {
                statement.execute("""
                        create index if not exists "idx_wcs_task_pending_priority"
                        on "wcs_task" ("app_id", "status", "is_valid", "priority" desc, "id" asc)
                        """);
                statement.execute("""
                        create index if not exists "idx_wcs_conn_log_state_cover"
                        on "wcs_connection_log" ("app_id", "event", "create_time", "id", "app_code", "state")
                        """);
            } else {
                throw new IllegalStateException("Unsupported database for WCS indexes: " + database);
            }
        }
    }

    private static void createPostgresIndex(Connection connection, String name, String ddl) throws Exception {
        try (Statement statement = connection.createStatement()) {
            if (Boolean.FALSE.equals(indexValid(statement, name))) {
                statement.execute("drop index concurrently if exists \"" + name + "\"");
            }
            statement.execute(ddl);
            if (!Boolean.TRUE.equals(indexValid(statement, name))) {
                throw new IllegalStateException("WCS index was not created as valid: " + name);
            }
        }
    }

    private static Boolean indexValid(Statement statement, String name) throws Exception {
        try (ResultSet result = statement.executeQuery("select i.indisvalid from pg_index i "
                + "where i.indexrelid = to_regclass('" + name + "')")) {
            return result.next() ? result.getBoolean(1) : null;
        }
    }
}
