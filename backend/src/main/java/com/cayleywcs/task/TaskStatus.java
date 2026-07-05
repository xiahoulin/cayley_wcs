package com.cayleywcs.task;

/** 任务状态字面量单一事实源（列 wcs_task.status）。 */
public final class TaskStatus {
    public static final String PENDING = "pending";
    /** M10 预留：链段已物化未激活，不可被引擎选中。 */
    public static final String WAITING = "waiting";
    public static final String DISPATCHED = "dispatched";
    public static final String EXECUTING = "executing";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    public static final String CANCELLED = "cancelled";

    private TaskStatus() {
    }
}
