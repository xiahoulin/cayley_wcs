package com.cayleywcs.common.support;

import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL reference writes run concurrently; deletion excludes them until commit. */
@Component
public class ResourceLifecycleGuard {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final boolean postgres;

    public ResourceLifecycleGuard(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transactions = new TransactionTemplate(manager);
        postgres = Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                connection -> connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
    }

    public void references() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Resource references require a transaction");
        jdbc.queryForObject("select id from wcs_resource_lifecycle_lock where id=1 "
                + (postgres ? "for share" : "for update"), Integer.class);
    }

    public <T> T referenceWrite(Supplier<T> work) {
        return transactions.execute(status -> { references(); return work.get(); });
    }

    public <T> T deletion(Supplier<T> work) {
        return transactions.execute(status -> {
            jdbc.queryForObject("select id from wcs_resource_lifecycle_lock where id=1 for update", Integer.class);
            return work.get();
        });
    }

    public static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }
}
