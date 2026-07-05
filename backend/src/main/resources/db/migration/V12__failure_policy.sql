-- M11 设备维护与统计：失败处理策略 + 在飞任务停滞超时。
-- failure_policy：manual=失败转人工（报警提示，默认）；auto_retry=自动重派（退避后 resetForRetry，
--   任务级 wcs_task.max_retry>0 优先，否则用应用级 wcs_application.max_retry 作为重试上限——该列自此承担
--   「任务重试默认上限」语义（此前无消费方）。
-- task_stale_seconds：在飞任务(last_update_time)停滞超此秒数判 failed+报警(-4)；0=关闭。

alter table "wcs_application" add column if not exists "failure_policy" varchar(32) not null default 'manual';
alter table "wcs_application" add column if not exists "task_stale_seconds" bigint not null default 0;

-- 报文留存清理谓词索引（M11：既有 idx_wcs_msg_log_app 前导列 app_id 对按时间清理不可用）
create index if not exists "idx_wcs_msg_log_ctime" on "wcs_message_log" ("create_time");

insert into "wcs_dict_type" ("type_code", "type_name", "sort")
select 'failure_policy', '失败处理策略', 14
where not exists (select 1 from "wcs_dict_type" where "type_code" = 'failure_policy');

insert into "wcs_dict_item" ("type_code", "item_code", "item_name", "item_value", "sort", "remark")
select 'failure_policy', 'manual', '转人工', 'manual', 1, '失败保持终态，报警提示人工处理（默认）'
where not exists (select 1 from "wcs_dict_item" where "type_code"='failure_policy' and "item_code"='manual');
insert into "wcs_dict_item" ("type_code", "item_code", "item_name", "item_value", "sort", "remark")
select 'failure_policy', 'auto_retry', '自动重试', 'auto_retry', 2, '退避后自动重派（上限=任务 max_retry>0 ? 任务级 : 应用 max_retry）'
where not exists (select 1 from "wcs_dict_item" where "type_code"='failure_policy' and "item_code"='auto_retry');

-- 充电语义预留（agv/shuttle）：任务类型字典加 charge 项，调度逻辑后置
insert into "wcs_dict_item" ("type_code", "item_code", "item_name", "item_value", "sort", "remark")
select 'task_type', 'charge', '充电（预留）', '7', 7, '预留：AGV/穿梭车低电量充电任务，配合 status_Battery 点位，调度逻辑后置'
where not exists (select 1 from "wcs_dict_item" where "type_code"='task_type' and "item_code"='charge');
