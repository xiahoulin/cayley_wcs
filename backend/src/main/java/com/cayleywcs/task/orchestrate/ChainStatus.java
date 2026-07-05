package com.cayleywcs.task.orchestrate;

/** 任务链状态字面量单一事实源（列 wcs_task_chain.status）。 */
public final class ChainStatus {
    public static final String PENDING = "pending";
    public static final String RUNNING = "running";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    public static final String CANCELLED = "cancelled";

    private ChainStatus() {
    }

    public static boolean terminal(String status) {
        return COMPLETED.equals(status) || FAILED.equals(status) || CANCELLED.equals(status);
    }
}
